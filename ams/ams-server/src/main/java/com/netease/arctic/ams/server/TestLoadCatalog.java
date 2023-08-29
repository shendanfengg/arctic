package com.netease.arctic.ams.server;

import com.netease.arctic.catalog.ArcticCatalog;
import com.netease.arctic.catalog.CatalogLoader;

public class TestLoadCatalog {
  public static void main(String[] args) {
    ArcticCatalog catalog = CatalogLoader.load("zookeeper://zk-fb369c10.arch.baichuan.ynode.cn/arctic-hechao/arctic_hechao_test_server");
    System.out.println("success");
  }
}
