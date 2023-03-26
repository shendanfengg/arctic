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

package com.netease.arctic.io;

import com.netease.arctic.table.TableIdentifier;
import org.junit.Assert;
import org.junit.Test;

public class TableTrashManagersTest {

  @Test
  public void getTrashLocation() {
    TableIdentifier id = TableIdentifier.of("test_catalog", "test_db", "test_table");
    Assert.assertEquals("/table/location/.trash",
        TableTrashManagers.getTrashLocation(id, "/table/location", null));
    Assert.assertEquals(String.format("/tmp/xxx/%s/%s/%s/.trash", id.getCatalog(), id.getDatabase(), id.getTableName()),
        TableTrashManagers.getTrashLocation(id, "/table/location", "/tmp/xxx"));
    Assert.assertEquals(String.format("/tmp/xxx/%s/%s/%s/.trash", id.getCatalog(), id.getDatabase(), id.getTableName()),
        TableTrashManagers.getTrashLocation(id, "/table/location", "/tmp/xxx/"));
  }

  @Test
  public void getTrashParentLocation() {
    TableIdentifier id = TableIdentifier.of("test_catalog", "test_db", "test_table");
    Assert.assertEquals(String.format("/tmp/xxx/%s/%s/%s", id.getCatalog(), id.getDatabase(), id.getTableName()),
        TableTrashManagers.getTrashParentLocation(id, "/tmp/xxx"));
    Assert.assertEquals(String.format("/tmp/xxx/%s/%s/%s", id.getCatalog(), id.getDatabase(), id.getTableName()),
        TableTrashManagers.getTrashParentLocation(id, "/tmp/xxx/"));
  }
}