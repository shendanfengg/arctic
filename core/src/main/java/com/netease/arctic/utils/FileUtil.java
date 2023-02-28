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

package com.netease.arctic.utils;

import com.netease.arctic.data.DataFileType;
import com.netease.arctic.data.DataTreeNode;
import com.netease.arctic.data.DefaultKeyedFile;
import com.netease.arctic.io.ArcticFileIO;
import org.apache.hadoop.fs.Path;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URI;
import java.util.Collections;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FileUtil {
  private static final Logger LOG = LoggerFactory.getLogger(FileUtil.class);

  private static final String KEYED_FILE_NAME_PATTERN_STRING = "(\\d+)-(\\w+)-(\\d+)-(\\d+)-(\\d+)-(\\d+)\\.\\w+";
  private static final Pattern KEYED_FILE_NAME_PATTERN = Pattern.compile(KEYED_FILE_NAME_PATTERN_STRING);

  /**
   * Parse file name form file path
   *
   * @param filePath file path
   * @return file name parsed from file path
   */
  public static String getFileName(String filePath) {
    int lastSlash = filePath.lastIndexOf('/');
    return filePath.substring(lastSlash + 1);
  }

  /**
   * Parse file directory path from file path
   *
   * @param filePath file path
   * @return file directory path parsed from file path
   */
  public static String getFileDir(String filePath) {
    int lastSlash = filePath.lastIndexOf('/');
    return filePath.substring(0, lastSlash);
  }

  public static String getPartitionPathFromFilePath(String fileLocation, String tableLocation, String fileName) {
    int tableIndex = fileLocation.indexOf(tableLocation);
    int fileIndex = fileLocation.lastIndexOf(fileName);
    return fileLocation.substring(tableIndex + tableLocation.length(), fileIndex - 1);
  }

  public static void deleteEmptyDirectory(ArcticFileIO io, String directoryPath) {
    deleteEmptyDirectory(io, directoryPath, Collections.emptySet());
  }

  /**
   * Try to recursiveDelete the empty directory
   *
   * @param io   arcticTableFileIo
   * @param directoryPath directory location
   * @param exclude the directory will not be deleted
   */
  public static void deleteEmptyDirectory(ArcticFileIO io, String directoryPath, Set<String> exclude) {
    Preconditions.checkArgument(io.exists(directoryPath), "The target directory is not exist");
    Preconditions.checkArgument(io.isDirectory(directoryPath), "The target path is not directory");
    String parent = new Path(directoryPath).getParent().toString();
    if (exclude.contains(directoryPath) || exclude.contains(parent)) {
      return;
    }

    LOG.debug("current path {} and parent path {} not in exclude.", directoryPath, parent);
    if (io.isEmptyDirectory(directoryPath)) {
      io.deleteFileWithResult(directoryPath, true);
      LOG.debug("success delete empty directory {}", directoryPath);
      deleteEmptyDirectory(io, parent, exclude);
    }
  }

  /**
   * Get the file path after move file to target directory
   * @param newDirectory target directory
   * @param filePath file
   * @return new file path
   */
  public static String getNewFilePath(String newDirectory, String filePath) {
    return newDirectory + File.separator + getFileName(filePath);
  }

  /**
   * parse keyed file meta from file name
   * @param fileName - keyed file name
   * @return fileMeta
   */
  public static DefaultKeyedFile.FileMeta parseFileMetaFromFileName(String fileName) {
    fileName = FileUtil.getFileName(fileName);
    Matcher matcher = KEYED_FILE_NAME_PATTERN.matcher(fileName);
    long nodeId = 1;
    DataFileType type = DataFileType.BASE_FILE;
    long transactionId = 0L;
    if (matcher.matches()) {
      nodeId = Long.parseLong(matcher.group(1));
      type = DataFileType.ofShortName(matcher.group(2));
      transactionId = Long.parseLong(matcher.group(3));
    }
    DataTreeNode node = DataTreeNode.ofId(nodeId);
    return new DefaultKeyedFile.FileMeta(transactionId, type, node);
  }

  /**
   * parse keyed file type from file name
   * @param fileName fileName
   * @return DataFileType
   */
  public static DataFileType parseFileTypeFromFileName(String fileName) {
    fileName = FileUtil.getFileName(fileName);
    Matcher matcher = KEYED_FILE_NAME_PATTERN.matcher(fileName);
    DataFileType type = DataFileType.BASE_FILE;
    if (matcher.matches()) {
      type = DataFileType.ofShortName(matcher.group(2));
    }
    return type;
  }

  /**
   * parse keyed file transaction id from file name
   * @param fileName fileName
   * @return transaction id
   */
  public static long parseFileTidFromFileName(String fileName) {
    fileName = FileUtil.getFileName(fileName);
    Matcher matcher = KEYED_FILE_NAME_PATTERN.matcher(fileName);
    long transactionId = 0L;
    if (matcher.matches()) {
      transactionId = Long.parseLong(matcher.group(3));
    }
    return transactionId;
  }

  /**
   * parse keyed file node id from file name
   * @param fileName fileName
   * @return node id
   */
  public static DataTreeNode parseFileNodeFromFileName(String fileName) {
    fileName = FileUtil.getFileName(fileName);
    Matcher matcher = KEYED_FILE_NAME_PATTERN.matcher(fileName);
    long nodeId = 1;
    if (matcher.matches()) {
      nodeId = Long.parseLong(matcher.group(1));
    }
    return DataTreeNode.ofId(nodeId);
  }

  /**
   * remove Uniform Resource Identifier (URI) in file path
   * @param path file path with Uniform Resource Identifier (URI)
   * @return file path without Uniform Resource Identifier (URI)
   */
  public static String getUriPath(String path) {
    return URI.create(path).getPath();
  }
}
