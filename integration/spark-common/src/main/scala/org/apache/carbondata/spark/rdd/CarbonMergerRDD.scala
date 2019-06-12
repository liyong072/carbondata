/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.carbondata.spark.rdd

import java.io.IOException
import java.util
import java.util.{Collections, List, Map}
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.mutable
import scala.collection.JavaConverters._
import scala.reflect.classTag

import org.apache.hadoop.mapred.JobConf
import org.apache.hadoop.mapreduce.{InputSplit, Job}
import org.apache.spark._
import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.execution.command.{CarbonMergerMapping, NodeInfo}
import org.apache.spark.sql.hive.DistributionUtil
import org.apache.spark.sql.util.{CarbonException, SparkTypeConverter}

import org.apache.carbondata.common.logging.LogServiceFactory
import org.apache.carbondata.converter.SparkDataTypeConverterImpl
import org.apache.carbondata.core.constants.{CarbonCommonConstants, SortScopeOptions}
import org.apache.carbondata.core.datamap.Segment
import org.apache.carbondata.core.datastore.block._
import org.apache.carbondata.core.datastore.impl.FileFactory
import org.apache.carbondata.core.indexstore.PartitionSpec
import org.apache.carbondata.core.metadata.{AbsoluteTableIdentifier, CarbonTableIdentifier}
import org.apache.carbondata.core.metadata.blocklet.DataFileFooter
import org.apache.carbondata.core.metadata.datatype.{DataType, DataTypes}
import org.apache.carbondata.core.metadata.schema.table.CarbonTable
import org.apache.carbondata.core.metadata.schema.table.column.{CarbonColumn, CarbonDimension, ColumnSchema}
import org.apache.carbondata.core.mutate.UpdateVO
import org.apache.carbondata.core.scan.expression
import org.apache.carbondata.core.scan.expression.Expression
import org.apache.carbondata.core.scan.result.iterator.RawResultIterator
import org.apache.carbondata.core.statusmanager.{FileFormat, LoadMetadataDetails, SegmentStatusManager, SegmentUpdateStatusManager}
import org.apache.carbondata.core.util.{CarbonProperties, CarbonUtil, DataTypeUtil}
import org.apache.carbondata.core.util.path.CarbonTablePath
import org.apache.carbondata.hadoop.{CarbonInputSplit, CarbonMultiBlockSplit, CarbonProjection}
import org.apache.carbondata.hadoop.api.{CarbonInputFormat, CarbonTableInputFormat}
import org.apache.carbondata.hadoop.util.{CarbonInputFormatUtil, CarbonInputSplitTaskInfo}
import org.apache.carbondata.processing.loading.TableProcessingOperations
import org.apache.carbondata.processing.loading.model.CarbonLoadModel
import org.apache.carbondata.processing.merger._
import org.apache.carbondata.processing.util.{CarbonDataProcessorUtil, CarbonLoaderUtil}
import org.apache.carbondata.spark.MergeResult
import org.apache.carbondata.spark.load.{ByteArrayOrdering, DataLoadProcessBuilderOnSpark, PrimtiveOrdering, StringOrdering}
import org.apache.carbondata.spark.util.{CarbonScalaUtil, CommonUtil, Util}

class CarbonMergerRDD[K, V](
    @transient private val ss: SparkSession,
    result: MergeResult[K, V],
    carbonLoadModel: CarbonLoadModel,
    carbonMergerMapping: CarbonMergerMapping)
  extends CarbonRDD[(K, V)](ss, Nil) {

  ss.sparkContext.setLocalProperty("spark.scheduler.pool", "DDL")
  ss.sparkContext.setLocalProperty("spark.job.interruptOnCancel", "true")

  private val queryId = sparkContext.getConf.get("queryId", System.nanoTime() + "")
  var storeLocation: String = null
  var mergeResult: String = null
  val tablePath = carbonMergerMapping.hdfsStoreLocation
  val metadataFilePath = carbonMergerMapping.metadataFilePath
  val mergedLoadName = carbonMergerMapping.mergedLoadName
  val databaseName = carbonMergerMapping.databaseName
  val factTableName = carbonMergerMapping.factTableName
  val tableId = carbonMergerMapping.tableId
  var expressionMapForRangeCol: util.Map[Integer, Expression] = null

  override def internalCompute(theSplit: Partition, context: TaskContext): Iterator[(K, V)] = {
    val queryStartTime = System.currentTimeMillis()
    val LOGGER = LogServiceFactory.getLogService(this.getClass.getName)
    val iter = new Iterator[(K, V)] {
      val carbonTable = carbonLoadModel.getCarbonDataLoadSchema.getCarbonTable
      val rangeColumn = carbonTable.getRangeColumn
      val carbonSparkPartition = theSplit.asInstanceOf[CarbonSparkPartition]
      if (carbonTable.isPartitionTable) {
        carbonLoadModel.setTaskNo(String.valueOf(carbonSparkPartition.partitionId))
      } else {
        carbonLoadModel.setTaskNo(String.valueOf(theSplit.index))
      }
      val partitionSpec = if (carbonTable.isHivePartitionTable) {
        carbonSparkPartition.partitionSpec.get
      } else {
        null
      }

      var mergeStatus = false
      var mergeNumber = ""
      var exec: CarbonCompactionExecutor = null
      var processor: AbstractResultProcessor = null
      var rawResultIteratorMap: util.Map[String, util.List[RawResultIterator]] = _
      try {
        // sorting the table block info List.
        val splitList = carbonSparkPartition.split.value.getAllSplits
        val tableBlockInfoList = CarbonInputSplit.createBlocks(splitList)

        Collections.sort(tableBlockInfoList)

        // During UPDATE DELTA COMPACTION case all the blocks received in compute belongs to
        // one segment, so max cardinality will be calculated from first block of segment
        if (CompactionType.IUD_UPDDEL_DELTA == carbonMergerMapping.campactionType) {
          var dataFileFooter: DataFileFooter = null
          try {
            // As the tableBlockInfoList is sorted take the ColCardinality from the last
            // Block of the sorted list as it will have the last updated cardinality.
            // Blocks are sorted by order of updation using TableBlockInfo.compare method so
            // the last block after the sort will be the latest one.
            dataFileFooter = CarbonUtil
              .readMetadataFile(tableBlockInfoList.get(tableBlockInfoList.size() - 1))
          } catch {
            case e: IOException =>
              logError("Exception in preparing the data file footer for compaction " + e.getMessage)
              throw e
          }
          // target load name will be same as source load name in case of update data compaction
          carbonMergerMapping.mergedLoadName = tableBlockInfoList.get(0).getSegmentId
          carbonMergerMapping.maxSegmentColCardinality = dataFileFooter.getSegmentInfo
            .getColumnCardinality
          carbonMergerMapping.maxSegmentColumnSchemaList = dataFileFooter.getColumnInTable.asScala
            .toList
        }
        mergeNumber = if (CompactionType.IUD_UPDDEL_DELTA == carbonMergerMapping.campactionType) {
          tableBlockInfoList.get(0).getSegment.toString
        } else {
          mergedLoadName.substring(
            mergedLoadName.lastIndexOf(CarbonCommonConstants.LOAD_FOLDER) +
              CarbonCommonConstants.LOAD_FOLDER.length(),
            mergedLoadName.length()
          )
        }
        carbonLoadModel.setSegmentId(mergeNumber)

        if(carbonTable.isHivePartitionTable) {
          carbonLoadModel.setTaskNo(
              CarbonScalaUtil.generateUniqueNumber(
                  theSplit.index,
                  mergeNumber.replace(".", ""), 0L))
        }

        CommonUtil.setTempStoreLocation(theSplit.index, carbonLoadModel, true, false)

        // get destination segment properties as sent from driver which is of last segment.
        val segmentProperties = new SegmentProperties(
          carbonMergerMapping.maxSegmentColumnSchemaList.asJava,
          carbonMergerMapping.maxSegmentColCardinality)

        val segmentMapping: java.util.Map[String, TaskBlockInfo] =
          CarbonCompactionUtil.createMappingForSegments(tableBlockInfoList)

        val dataFileMetadataSegMapping: java.util.Map[String, List[DataFileFooter]] =
          CarbonCompactionUtil.createDataFileFooterMappingForSegments(tableBlockInfoList,
            carbonTable.getSortScope != SortScopeOptions.SortScope.NO_SORT)

        carbonLoadModel.setTablePath(tablePath)
        // check for restructured block
        // TODO: only in case of add and drop this variable should be true
        val restructuredBlockExists: Boolean = CarbonCompactionUtil
          .checkIfAnyRestructuredBlockExists(segmentMapping,
            dataFileMetadataSegMapping,
            carbonTable.getTableLastUpdatedTime)
        LOGGER.info(s"Restructured block exists: $restructuredBlockExists")
        DataTypeUtil.setDataTypeConverter(new SparkDataTypeConverterImpl)
        exec = new CarbonCompactionExecutor(segmentMapping, segmentProperties,
          carbonTable, dataFileMetadataSegMapping, restructuredBlockExists,
          new SparkDataTypeConverterImpl)

        // add task completion listener to clean up the resources
        context.addTaskCompletionListener { _ =>
          close()
        }
        try {
          // fire a query and get the results.
          var expr: expression.Expression = null
          if (null != expressionMapForRangeCol) {
            expr = expressionMapForRangeCol
              .get(theSplit.asInstanceOf[CarbonSparkPartition].idx)
          }
          rawResultIteratorMap = exec.processTableBlocks(FileFactory.getConfiguration, expr)
        } catch {
          case e: Throwable =>
            LOGGER.error(e)
            if (null != e.getMessage) {
              CarbonException.analysisException(
                s"Exception occurred in query execution :: ${ e.getMessage }")
            } else {
              CarbonException.analysisException(
                "Exception occurred in query execution.Please check logs.")
            }
        }

        val tempStoreLoc = CarbonDataProcessorUtil.getLocalDataFolderLocation(
          carbonTable, carbonLoadModel.getTaskNo, mergeNumber, true, false)

        if (carbonTable.getSortScope == SortScopeOptions.SortScope.NO_SORT ||
          rawResultIteratorMap.get(CarbonCompactionUtil.UNSORTED_IDX).size() == 0) {

          LOGGER.info("RowResultMergerProcessor flow is selected")
          processor = new RowResultMergerProcessor(
            databaseName,
            factTableName,
            segmentProperties,
            tempStoreLoc,
            carbonLoadModel,
            carbonMergerMapping.campactionType,
            partitionSpec)

        } else {

          LOGGER.info("CompactionResultSortProcessor flow is selected")
          processor = new CompactionResultSortProcessor(
            carbonLoadModel,
            carbonTable,
            segmentProperties,
            carbonMergerMapping.campactionType,
            factTableName,
            partitionSpec)

        }

        mergeStatus = processor.execute(
          rawResultIteratorMap.get(CarbonCompactionUtil.UNSORTED_IDX),
          rawResultIteratorMap.get(CarbonCompactionUtil.SORTED_IDX))
        mergeResult = tableBlockInfoList.get(0).getSegmentId + ',' + mergeNumber

      } catch {
        case e: Exception =>
          LOGGER.error("Compaction Failed ", e)
          throw e
      }

      private def close(): Unit = {
        deleteLocalDataFolders()
        // close all the query executor service and clean up memory acquired during query processing
        if (null != exec) {
          LOGGER.info("Cleaning up query resources acquired during compaction")
          exec.close(rawResultIteratorMap.get(CarbonCompactionUtil.UNSORTED_IDX), queryStartTime)
          exec.close(rawResultIteratorMap.get(CarbonCompactionUtil.SORTED_IDX), queryStartTime)
        }
        // clean up the resources for processor
        if (null != processor) {
          LOGGER.info("Closing compaction processor instance to clean up loading resources")
          processor.close()
        }
      }

      private def deleteLocalDataFolders(): Unit = {
        try {
          LOGGER.info("Deleting local folder store location")
          val isCompactionFlow = true
          TableProcessingOperations
            .deleteLocalDataLoadFolderLocation(carbonLoadModel, isCompactionFlow, false)
        } catch {
          case e: Exception =>
            LOGGER.error(e)
        }
      }

      var finished = false

      override def hasNext: Boolean = {
        !finished
      }

      override def next(): (K, V) = {
        finished = true
        result.getKey(mergeResult, mergeStatus)
      }
    }
    iter
  }

  override def internalGetPartitions: Array[Partition] = {
    val startTime = System.currentTimeMillis()
    val absoluteTableIdentifier: AbsoluteTableIdentifier = AbsoluteTableIdentifier.from(
      tablePath, new CarbonTableIdentifier(databaseName, factTableName, tableId)
    )
    val carbonTable = carbonLoadModel.getCarbonDataLoadSchema.getCarbonTable
    var rangeColumn: CarbonColumn = null
    if (!carbonLoadModel.getCarbonDataLoadSchema.getCarbonTable.isHivePartitionTable) {
      // If the table is not a partition table then only we go for range column compaction flow
      rangeColumn = carbonTable.getRangeColumn
    }
    val dataType: DataType = if (null != rangeColumn) {
      rangeColumn.getDataType
    } else {
      null
    }
    val isRangeColSortCol = rangeColumn != null && rangeColumn.isDimension &&
                            rangeColumn.asInstanceOf[CarbonDimension].isSortColumn
    val updateStatusManager: SegmentUpdateStatusManager = new SegmentUpdateStatusManager(
      carbonTable)
    val jobConf: JobConf = new JobConf(getConf)
    SparkHadoopUtil.get.addCredentials(jobConf)
    val job: Job = new Job(jobConf)
    val format = CarbonInputFormatUtil.createCarbonInputFormat(absoluteTableIdentifier, job)
    CarbonInputFormat.setPartitionsToPrune(
      job.getConfiguration,
      carbonMergerMapping.currentPartitions.map(_.asJava).orNull)
    CarbonInputFormat.setTableInfo(job.getConfiguration,
      carbonLoadModel.getCarbonDataLoadSchema.getCarbonTable.getTableInfo)
    var updateDetails: UpdateVO = null
    // initialise query_id for job
    job.getConfiguration.set("query.id", queryId)
    var defaultParallelism = sparkContext.defaultParallelism
    val result = new java.util.ArrayList[Partition](defaultParallelism)
    var taskPartitionNo = 0
    var carbonPartitionId = 0
    var noOfBlocks = 0

    val taskInfoList = new java.util.ArrayList[Distributable]
    var carbonInputSplits = mutable.Seq[CarbonInputSplit]()
    var allSplits = new java.util.ArrayList[InputSplit]

    var splitsOfLastSegment: List[CarbonInputSplit] = null
    // map for keeping the relation of a task and its blocks.
    val taskIdMapping: java.util.Map[String, java.util.List[CarbonInputSplit]] = new
        java.util.HashMap[String, java.util.List[CarbonInputSplit]]

    var totalSize: Double = 0
    var totalTaskCount: Integer = 0
    var loadMetadataDetails: Array[LoadMetadataDetails] = null
    // Only for range column get the details for the size of segments
    if (null != rangeColumn) {
      loadMetadataDetails = SegmentStatusManager
        .readLoadMetadata(CarbonTablePath.getMetadataPath(tablePath))
    }
    // for each valid segment.
    for (eachSeg <- carbonMergerMapping.validSegments) {
      // In case of range column get the size for calculation of number of ranges
      if (null != rangeColumn) {
        for (details <- loadMetadataDetails) {
          if (details.getLoadName == eachSeg.getSegmentNo) {
            totalSize = totalSize + (details.getDataSize.toDouble)
          }
        }
      }

      // map for keeping the relation of a task and its blocks.
      job.getConfiguration.set(CarbonTableInputFormat.INPUT_SEGMENT_NUMBERS, eachSeg.getSegmentNo)

      if (updateStatusManager.getUpdateStatusDetails.length != 0) {
         updateDetails = updateStatusManager.getInvalidTimestampRange(eachSeg.getSegmentNo)
      }

      val updated: Boolean = updateStatusManager.getUpdateStatusDetails.length != 0
      // get splits
      val splits = format.getSplits(job)

      // keep on assigning till last one is reached.
      if (null != splits && splits.size > 0) {
        splitsOfLastSegment = splits.asScala
          .map(_.asInstanceOf[CarbonInputSplit])
          .filter { split => FileFormat.COLUMNAR_V3.equals(split.getFileFormat) }.toList.asJava
      }
       val filteredSplits = splits.asScala.map(_.asInstanceOf[CarbonInputSplit]).filter{ entry =>
        val blockInfo = new TableBlockInfo(entry.getFilePath,
          entry.getStart, entry.getSegmentId,
          entry.getLocations, entry.getLength, entry.getVersion,
          updateStatusManager.getDeleteDeltaFilePath(
            entry.getFilePath,
            Segment.toSegment(entry.getSegmentId).getSegmentNo)
        )
        (!updated || (updated && (!CarbonUtil
          .isInvalidTableBlock(blockInfo.getSegmentId, blockInfo.getFilePath,
            updateDetails, updateStatusManager)))) &&
        FileFormat.COLUMNAR_V3.equals(entry.getFileFormat)
      }
      if (rangeColumn != null) {
        totalTaskCount = totalTaskCount +
                         CarbonCompactionUtil.getTaskCountForSegment(filteredSplits.toArray)
      }
      carbonInputSplits ++:= filteredSplits
      allSplits.addAll(filteredSplits.asJava)
    }
    totalTaskCount = totalTaskCount / carbonMergerMapping.validSegments.size
    val LOGGER = LogServiceFactory.getLogService(this.getClass.getName)
    var allRanges: Array[Object] = new Array[Object](0)
    var singleRange = false
    if (rangeColumn != null) {
      // Calculate the number of ranges to be made, min 2 ranges/tasks to be made in any case
      // We take the minimum of average number of tasks created during load time and the number
      // of tasks we get based on size for creating ranges.
      val numOfPartitions = Math
        .max(CarbonCommonConstants.NUM_CORES_DEFAULT_VAL.toInt,
          Math.min(totalTaskCount, DataLoadProcessBuilderOnSpark
            .getNumPatitionsBasedOnSize(totalSize, carbonTable, carbonLoadModel, true)))
      val colName = rangeColumn.getColName
      LOGGER.info(s"Compacting on range column: $colName")
      allRanges = getRangesFromRDD(rangeColumn,
        carbonTable,
        numOfPartitions,
        allSplits,
        dataType)
      // If RangePartitioner does not give ranges in the case when the data is skewed with
      // a lot of null records then we take the min/max from footer and set them for tasks
      if (null == allRanges || allRanges.size == 1) {
        allRanges = CarbonCompactionUtil.getOverallMinMax(carbonInputSplits.toList.toArray,
          rangeColumn,
          isRangeColSortCol)
        if(allRanges(0) == allRanges(1)) {
          // This will be true only if data has single values throughout
          singleRange = true
        }
      }
      LOGGER.info(s"Number of ranges:" + allRanges.size)
    }

    // prepare the details required to extract the segment properties using last segment.
    if (null != splitsOfLastSegment && splitsOfLastSegment.size() > 0) {
      val carbonInputSplit = splitsOfLastSegment.get(0)
      var dataFileFooter: DataFileFooter = null

      try {
        dataFileFooter = CarbonUtil.readMetadataFile(
          CarbonInputSplit.getTableBlockInfo(carbonInputSplit))
      } catch {
        case e: IOException =>
          logError("Exception in preparing the data file footer for compaction " + e.getMessage)
          throw e
      }
    }

    val columnToCardinalityMap = new util.HashMap[java.lang.String, Integer]()
    val partitionTaskMap = new util.HashMap[PartitionSpec, String]()
    val counter = new AtomicInteger()
    var indexOfRangeColumn = -1
    var taskIdCount = 0
    // As we are already handling null values in the filter expression separately so we
    // can remove the null from the ranges we get, else it may lead to duplicate data
    val newRanges = allRanges.filter { range =>
      range != null
    }
    val noOfSplitsPerTask = Math.ceil(carbonInputSplits.size / defaultParallelism)
    var taskCount = 0
    // In case of range column if only one data value is present then we try to
    // divide the splits to different tasks in order to avoid single task creation
    // and load on single executor
    if (singleRange) {
      carbonInputSplits.foreach { split =>
        var dataFileFooter: DataFileFooter = null
        try {
          dataFileFooter = CarbonUtil.readMetadataFile(
            CarbonInputSplit.getTableBlockInfo(split))
        } catch {
          case e: IOException =>
            logError("Exception in preparing the data file footer for compaction " + e.getMessage)
            throw e
        }
        // add all the column and cardinality to the map
        CarbonCompactionUtil
          .addColumnCardinalityToMap(columnToCardinalityMap,
            dataFileFooter.getColumnInTable,
            dataFileFooter.getSegmentInfo.getColumnCardinality)

        var splitList = taskIdMapping.get(taskCount.toString)
        if (null != splitList && splitList.size == noOfSplitsPerTask) {
          taskCount = taskCount + 1
          splitList = taskIdMapping.get(taskCount.toString)
        }
        if (null == splitList) {
          splitList = new util.ArrayList[CarbonInputSplit]()
          taskIdMapping.put(taskCount.toString, splitList)
        }
        splitList.add(split)
      }
    } else {
      carbonInputSplits.foreach { split =>
        var dataFileFooter: DataFileFooter = null
        if (null == rangeColumn) {
          val taskNo = getTaskNo(split, partitionTaskMap, counter)
          var sizeOfSplit = split.getLength
          val splitList = taskIdMapping.get(taskNo)
          noOfBlocks += 1
          if (null == splitList) {
            val splitTempList = new util.ArrayList[CarbonInputSplit]()
            splitTempList.add(split)
            taskIdMapping.put(taskNo, splitTempList)
          } else {
            splitList.add(split)
          }
        }
        // Check the cardinality of each columns and set the highest.
        try {
          dataFileFooter = CarbonUtil.readMetadataFile(
            CarbonInputSplit.getTableBlockInfo(split))
        } catch {
          case e: IOException =>
            logError("Exception in preparing the data file footer for compaction " + e.getMessage)
            throw e
        }
        // add all the column and cardinality to the map
        CarbonCompactionUtil
          .addColumnCardinalityToMap(columnToCardinalityMap,
            dataFileFooter.getColumnInTable,
            dataFileFooter.getSegmentInfo.getColumnCardinality)

        // Create taskIdMapping here for range column by reading min/max values.
        if (null != rangeColumn) {
          if (null == expressionMapForRangeCol) {
            expressionMapForRangeCol = new util.HashMap[Integer, Expression]()
          }
          if (-1 == indexOfRangeColumn) {
            val allColumns = dataFileFooter.getColumnInTable
            for (i <- 0 until allColumns.size()) {
              if (allColumns.get(i).getColumnName.equalsIgnoreCase(rangeColumn.getColName)) {
                indexOfRangeColumn = i
              }
            }
          }
          // Create ranges and add splits to the tasks
          for (i <- 0 until (newRanges.size + 1)) {
            if (null == expressionMapForRangeCol.get(i)) {
              // Creating FilterExpression for the range column
              var minVal: Object = null
              var maxVal: Object = null
              // For first task we will create an Or Filter and also accomodate null values
              // For last task we will take as GreaterThan Expression of last value
              if (i != 0) {
                minVal = newRanges(i - 1)
              }
              if (i != newRanges.size) {
                maxVal = newRanges(i)
              }
              val filterExpr = CarbonCompactionUtil
                .getFilterExpressionForRange(rangeColumn,
                  minVal, maxVal, dataType)
              expressionMapForRangeCol.put(i, filterExpr)
            }
            var splitList = taskIdMapping.get(i.toString)
            noOfBlocks += 1
            if (null == splitList) {
              splitList = new util.ArrayList[CarbonInputSplit]()
              taskIdMapping.put(i.toString, splitList)
            }
            splitList.add(split)
          }
        }
      }
    }
    val updatedMaxSegmentColumnList = new util.ArrayList[ColumnSchema]()
    // update cardinality and column schema list according to master schema
    val cardinality = CarbonCompactionUtil
      .updateColumnSchemaAndGetCardinality(columnToCardinalityMap,
        carbonTable,
        updatedMaxSegmentColumnList)
    carbonMergerMapping.maxSegmentColumnSchemaList = updatedMaxSegmentColumnList.asScala.toList
    // Set cardinality for new segment.
    carbonMergerMapping.maxSegmentColCardinality = cardinality

    taskIdMapping.asScala.foreach(
      entry =>
        taskInfoList
          .add(new CarbonInputSplitTaskInfo(entry._1, entry._2).asInstanceOf[Distributable])
    )

    // get all the active nodes of cluster and prepare the nodeBlockMap based on these nodes
    val activeNodes = DistributionUtil
      .ensureExecutorsAndGetNodeList(taskInfoList.asScala, sparkContext)

    val nodeBlockMap = CarbonLoaderUtil.nodeBlockMapping(taskInfoList, -1, activeNodes.asJava)

    val nodeTaskBlocksMap = new java.util.HashMap[String, java.util.List[NodeInfo]]()
    val nodes = DistributionUtil.getNodeList(sparkContext)
    logInfo("no.of.nodes where data present=" + nodeBlockMap.size())
    defaultParallelism = sparkContext.defaultParallelism

    val isPartitionTable = carbonLoadModel.getCarbonDataLoadSchema.getCarbonTable.isPartitionTable
    // Create Spark Partition for each task and assign blocks
    nodeBlockMap.asScala.foreach { case (nodeName, splitList) =>
      val taskSplitList = new java.util.ArrayList[NodeInfo](0)
      nodeTaskBlocksMap.put(nodeName, taskSplitList)
      var blockletCount = 0
      splitList.asScala.foreach { splitInfo =>
        val splitsPerNode = splitInfo.asInstanceOf[CarbonInputSplitTaskInfo]
        blockletCount = blockletCount + splitsPerNode.getCarbonInputSplitList.size()
        taskSplitList.add(
          NodeInfo(splitsPerNode.getTaskId, splitsPerNode.getCarbonInputSplitList.size()))

        if (blockletCount != 0) {
          val taskInfo = splitInfo.asInstanceOf[CarbonInputSplitTaskInfo]
          val multiBlockSplit = new CarbonMultiBlockSplit(
            taskInfo.getCarbonInputSplitList,
            Array(nodeName))
          if (isPartitionTable) {
            carbonPartitionId = Integer.parseInt(taskInfo.getTaskId)
          }
          result.add(
            new CarbonSparkPartition(
              id,
              taskPartitionNo,
              multiBlockSplit,
              carbonPartitionId,
              getPartitionNamesFromTask(taskInfo.getTaskId, partitionTaskMap)))
          taskPartitionNo += 1
        }
      }
    }

    // print the node info along with task and number of blocks for the task.
    nodeTaskBlocksMap.asScala.foreach((entry: (String, List[NodeInfo])) => {
      logInfo(s"for the node ${ entry._1 }")
      for (elem <- entry._2.asScala) {
        logInfo("Task ID is " + elem.TaskId + "no. of blocks is " + elem.noOfBlocks)
      }
    })

    val noOfNodes = nodes.length
    val noOfTasks = result.size
    logInfo(s"Identified  no.of.Blocks: $noOfBlocks," +
            s"parallelism: $defaultParallelism , no.of.nodes: $noOfNodes, no.of.tasks: $noOfTasks")
    logInfo("Time taken to identify Blocks to scan : " + (System.currentTimeMillis() - startTime))
    for (j <- 0 until result.size) {
      val multiBlockSplit = result.get(j).asInstanceOf[CarbonSparkPartition].split.value
      val splitList = multiBlockSplit.getAllSplits
      logInfo(s"Node: ${ multiBlockSplit.getLocations.mkString(",") }, No.Of Blocks: " +
              s"${ CarbonInputSplit.createBlocks(splitList).size }")
    }
    result.toArray(new Array[Partition](result.size))
  }

  private def getRangesFromRDD(rangeColumn: CarbonColumn,
      carbonTable: CarbonTable,
      defaultParallelism: Int,
      allSplits: java.util.ArrayList[InputSplit],
      dataType: DataType): Array[Object] = {
    val inputMetricsStats: CarbonInputMetrics = new CarbonInputMetrics
    val projection = new CarbonProjection
    projection.addColumn(rangeColumn.getColName)
    val scanRdd = new CarbonScanRDD[InternalRow](
      ss,
      projection,
      null,
      carbonTable.getAbsoluteTableIdentifier,
      carbonTable.getTableInfo.serialize(),
      carbonTable.getTableInfo,
      inputMetricsStats,
      partitionNames = null,
      splits = allSplits)
    val objectOrdering: Ordering[Object] = createOrderingForColumn(rangeColumn)
    val sparkDataType = Util.convertCarbonToSparkDataType(dataType)
    // Change string type to support all types
    val sampleRdd = scanRdd
      .map(row => (row.get(0, sparkDataType), null))
    val value = new DataSkewRangePartitioner(
      defaultParallelism, sampleRdd, true)(objectOrdering, classTag[Object])
    value.rangeBounds
  }

  private def createOrderingForColumn(column: CarbonColumn): Ordering[Object] = {
    if (column.isDimension) {
      val dimension = column.asInstanceOf[CarbonDimension]
      if ((dimension.isGlobalDictionaryEncoding || dimension.isDirectDictionaryEncoding) &&
          dimension.getDataType != DataTypes.TIMESTAMP) {
        new PrimtiveOrdering(DataTypes.INT)
      } else {
        if (DataTypeUtil.isPrimitiveColumn(column.getDataType)) {
          new PrimtiveOrdering(column.getDataType)
        } else {
          new StringOrdering()
        }
      }
    } else {
      new PrimtiveOrdering(column.getDataType)
    }
  }

  private def getTaskNo(
      split: CarbonInputSplit,
      partitionTaskMap: util.Map[PartitionSpec, String],
      counter: AtomicInteger): String = {
    if (carbonLoadModel.getCarbonDataLoadSchema.getCarbonTable.isHivePartitionTable) {
      val path = split.getPath.getParent
      val partTask =
        carbonMergerMapping.currentPartitions.get.find(p => p.getLocation.equals(path)) match {
        case Some(part) => part
        case None =>
          throw new UnsupportedOperationException("Cannot do compaction on dropped partition")
      }
      var task = partitionTaskMap.get(partTask)
      if (task == null) {
        task = counter.incrementAndGet().toString
        partitionTaskMap.put(partTask, task)
      }
      task
    } else {
      split.taskId
    }
  }

  private def getPartitionNamesFromTask(taskId: String,
      partitionTaskMap: util.Map[PartitionSpec, String]): Option[PartitionSpec] = {
    if (carbonLoadModel.getCarbonDataLoadSchema.getCarbonTable.isHivePartitionTable) {
      Some(partitionTaskMap.asScala.find(f => f._2.equals(taskId)).get._1)
    } else {
      None
    }
  }

  override def getPreferredLocations(split: Partition): Seq[String] = {
    val theSplit = split.asInstanceOf[CarbonSparkPartition]
    theSplit.split.value.getLocations.filter(_ != "localhost")
  }
}
