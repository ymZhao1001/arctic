/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netease.arctic.ams.server.optimize;

import com.netease.arctic.ams.api.DataFileInfo;
import com.netease.arctic.ams.api.OptimizeTaskId;
import com.netease.arctic.ams.api.TreeNode;
import com.netease.arctic.ams.api.properties.OptimizeTaskProperties;
import com.netease.arctic.ams.server.model.BasicOptimizeTask;
import com.netease.arctic.ams.server.model.FileTree;
import com.netease.arctic.ams.server.model.FilesStatistics;
import com.netease.arctic.ams.server.model.TableOptimizeRuntime;
import com.netease.arctic.ams.server.model.TaskConfig;
import com.netease.arctic.ams.server.utils.FilesStatisticsBuilder;
import com.netease.arctic.data.DataTreeNode;
import com.netease.arctic.table.ArcticTable;
import com.netease.arctic.table.TableProperties;
import com.netease.arctic.utils.SerializationUtils;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public abstract class AbstractArcticOptimizePlan extends AbstractOptimizePlan {

  protected final List<DataFileInfo> baseTableFileList;
  protected final List<DataFileInfo> changeTableFileList;
  protected final List<DataFileInfo> posDeleteFileList;
  // Whether to customize the directory
  protected boolean isCustomizeDir;
  // table file format
  protected String fileFormat;

  // partition -> fileTree
  protected final Map<String, FileTree> partitionFileTree = new LinkedHashMap<>();
  // partition -> position delete file
  protected final Map<String, List<DeleteFile>> partitionPosDeleteFiles = new LinkedHashMap<>();

  // for base table or unKeyed table
  private final long currentBaseSnapshotId;
  // for change table
  protected final long currentChangeSnapshotId;

  public AbstractArcticOptimizePlan(ArcticTable arcticTable, TableOptimizeRuntime tableOptimizeRuntime,
                                    List<DataFileInfo> baseTableFileList,
                                    List<DataFileInfo> changeTableFileList,
                                    List<DataFileInfo> posDeleteFileList,
                                    Map<String, Boolean> partitionTaskRunning,
                                    int queueId, long currentTime, long changeSnapshotId, long baseSnapshotId) {
    super(arcticTable, tableOptimizeRuntime, partitionTaskRunning, queueId, currentTime);
    this.baseTableFileList = baseTableFileList;
    this.changeTableFileList = changeTableFileList;
    this.posDeleteFileList = posDeleteFileList;
    this.isCustomizeDir = false;
    this.fileFormat = arcticTable.properties().getOrDefault(TableProperties.DEFAULT_FILE_FORMAT,
        TableProperties.DEFAULT_FILE_FORMAT_DEFAULT);
    this.currentChangeSnapshotId = changeSnapshotId;
    this.currentBaseSnapshotId = baseSnapshotId;
  }

  protected BasicOptimizeTask buildOptimizeTask(@Nullable List<DataTreeNode> sourceNodes,
                                                List<DataFile> insertFiles,
                                                List<DataFile> deleteFiles,
                                                List<DataFile> baseFiles,
                                                List<DeleteFile> posDeleteFiles,
                                                TaskConfig taskConfig) {
    // build task
    BasicOptimizeTask optimizeTask = new BasicOptimizeTask();
    optimizeTask.setTaskCommitGroup(taskConfig.getCommitGroup());
    optimizeTask.setTaskPlanGroup(taskConfig.getPlanGroup());
    optimizeTask.setCreateTime(taskConfig.getCreateTime());

    List<ByteBuffer> baseFileBytesList =
        baseFiles.stream()
            .map(SerializationUtils::toByteBuffer)
            .collect(Collectors.toList());
    List<ByteBuffer> insertFileBytesList =
        insertFiles.stream()
            .map(SerializationUtils::toByteBuffer)
            .collect(Collectors.toList());
    List<ByteBuffer> deleteFileBytesList =
        deleteFiles.stream()
            .map(SerializationUtils::toByteBuffer)
            .collect(Collectors.toList());
    List<ByteBuffer> posDeleteFileBytesList =
        posDeleteFiles.stream()
            .map(SerializationUtils::toByteBuffer)
            .collect(Collectors.toList());
    optimizeTask.setBaseFiles(baseFileBytesList);
    optimizeTask.setInsertFiles(insertFileBytesList);
    optimizeTask.setDeleteFiles(deleteFileBytesList);
    optimizeTask.setPosDeleteFiles(posDeleteFileBytesList);

    FilesStatisticsBuilder baseFb = new FilesStatisticsBuilder();
    FilesStatisticsBuilder insertFb = new FilesStatisticsBuilder();
    FilesStatisticsBuilder deleteFb = new FilesStatisticsBuilder();
    FilesStatisticsBuilder posDeleteFb = new FilesStatisticsBuilder();
    baseFiles.stream().map(DataFile::fileSizeInBytes)
        .forEach(baseFb::addFile);
    insertFiles.stream().map(DataFile::fileSizeInBytes)
        .forEach(insertFb::addFile);
    deleteFiles.stream().map(DataFile::fileSizeInBytes)
        .forEach(deleteFb::addFile);
    posDeleteFiles.stream().map(DeleteFile::fileSizeInBytes)
        .forEach(posDeleteFb::addFile);

    FilesStatistics baseFs = baseFb.build();
    FilesStatistics insertFs = insertFb.build();
    FilesStatistics deleteFs = deleteFb.build();
    FilesStatistics posDeleteFs = posDeleteFb.build();

    // file size
    optimizeTask.setBaseFileSize(baseFs.getTotalSize());
    optimizeTask.setInsertFileSize(insertFs.getTotalSize());
    optimizeTask.setDeleteFileSize(deleteFs.getTotalSize());
    optimizeTask.setPosDeleteFileSize(posDeleteFs.getTotalSize());

    // file count
    optimizeTask.setBaseFileCnt(baseFs.getFileCnt());
    optimizeTask.setInsertFileCnt(insertFs.getFileCnt());
    optimizeTask.setDeleteFileCnt(deleteFs.getFileCnt());
    optimizeTask.setPosDeleteFileCnt(posDeleteFs.getFileCnt());

    optimizeTask.setPartition(taskConfig.getPartition());
    optimizeTask.setQueueId(queueId);
    optimizeTask.setTaskId(new OptimizeTaskId(taskConfig.getOptimizeType(), UUID.randomUUID().toString()));
    optimizeTask.setTableIdentifier(arcticTable.id().buildTableIdentifier());

    // for keyed table
    if (sourceNodes != null) {
      optimizeTask.setSourceNodes(sourceNodes.stream()
          .map(node ->
              new TreeNode(node.getMask(), node.getIndex()))
          .collect(Collectors.toList()));
    }
    if (taskConfig.getMaxTransactionId() != null) {
      optimizeTask.setMaxChangeTransactionId(taskConfig.getMaxTransactionId());
    }

    if (taskConfig.getMinTransactionId() != null) {
      optimizeTask.setMinChangeTransactionId(taskConfig.getMinTransactionId());
    }

    // table ams url
    Map<String, String> properties = new HashMap<>();
    properties.put(OptimizeTaskProperties.ALL_FILE_COUNT, (optimizeTask.getBaseFiles().size() +
        optimizeTask.getInsertFiles().size() + optimizeTask.getDeleteFiles().size()) +
        optimizeTask.getPosDeleteFiles().size() + "");
    properties.put(OptimizeTaskProperties.CUSTOM_HIVE_SUB_DIRECTORY, taskConfig.getCustomHiveSubdirectory());
    optimizeTask.setProperties(properties);
    return optimizeTask;
  }

  public boolean tableNeedPlan() {
    return tableChanged();
  }

  /**
   * check whether node task need to build
   * @param posDeleteFiles pos-delete files in node
   * @param baseFiles base files in node
   * @return whether the node task need to build. If true, build task, otherwise skip.
   */
  protected abstract boolean nodeTaskNeedBuild(List<DeleteFile> posDeleteFiles, List<DataFile> baseFiles);

  protected abstract boolean tableChanged();

  protected boolean hasFileToOptimize() {
    return !partitionFileTree.isEmpty();
  }

  public long getCurrentSnapshotId() {
    return currentBaseSnapshotId;
  }

  public long getCurrentChangeSnapshotId() {
    return currentChangeSnapshotId;
  }

}