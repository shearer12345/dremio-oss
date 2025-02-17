/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.exec.planner.sql.handlers.refresh;


import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dremio.exec.planner.sql.handlers.SqlHandlerConfig;
import com.dremio.exec.record.BatchSchema;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.proto.NameSpaceContainer;
import com.dremio.service.users.SystemUser;
import com.google.common.base.Throwables;

/**
 * Given a table's logical name should interact with the KV-store to get schema, partition columns and other metadata
 */
public class UnlimitedSplitsMetadataProvider {
  private static final Logger logger = LoggerFactory.getLogger(UnlimitedSplitsMetadataProvider.class);

  private SqlHandlerConfig sqlHandlerConfig;
  private final NamespaceKey tableNSKey;
  private boolean metadataExists = false;

  private String tableUuid = UUID.randomUUID().toString();
  private BatchSchema schema = BatchSchema.EMPTY;
  private List<String> partitionCols = new ArrayList<>();
  private DatasetConfig config;

  public UnlimitedSplitsMetadataProvider(SqlHandlerConfig config, NamespaceKey tableNSKey) {
    sqlHandlerConfig = config;
    this.tableNSKey = tableNSKey;
    final NamespaceService nsService = config.getContext().getNamespaceService(SystemUser.SYSTEM_USERNAME);
    try {
      evaluateExistingMetadata(nsService);
    } catch (NamespaceException e) {
      Throwables.propagateIfPossible(e, RuntimeException.class);
    }
  }

  private void evaluateExistingMetadata(NamespaceService nsService) throws NamespaceException {
    config = null;
    if (nsService.exists(tableNSKey, NameSpaceContainer.Type.DATASET)) {
      config = nsService.getDataset(tableNSKey);
    }

    if (config == null) {
      logger.info("Table metadata for {} not found", tableNSKey.getSchemaPath());
      return;
    } else if (config.getPhysicalDataset() == null
      || !Boolean.TRUE.equals(config.getPhysicalDataset().getIcebergMetadataEnabled())) {
      logger.info("Forgetting current metadata for {}, it'll be re-promoted to use Iceberg to store metadata.", tableNSKey);
      return;
    }

    schema = BatchSchema.deserialize(config.getRecordSchema());
    String tableLocation = config.getPhysicalDataset().getIcebergMetadata().getMetadataFileLocation();
    tableUuid = config.getPhysicalDataset().getIcebergMetadata().getTableUuid();
    partitionCols = Optional.ofNullable(config.getReadDefinition().getPartitionColumnsList()).orElse(Collections.EMPTY_LIST);
    metadataExists = true;
    logger.info("Table metadata found for {}, at {}", tableNSKey.getSchemaPath(), tableLocation);
  }

  public Boolean doesMetadataExist() {
    return metadataExists;
  }

  public String getTableUUId() {
    return tableUuid;
  }

  public BatchSchema getTableSchema() {
    return schema;
  }

  public List<String> getPartitionColumns() {
    return partitionCols;
  }

  public DatasetConfig getDatasetConfig() {
    return config;
  }

  public void resetMetadata() {
    final NamespaceService nsService = sqlHandlerConfig.getContext().getNamespaceService(SystemUser.SYSTEM_USERNAME);
    try {
      evaluateExistingMetadata(nsService);
    } catch (NamespaceException e) {
      Throwables.propagateIfPossible(e, RuntimeException.class);
    }
  }

}
