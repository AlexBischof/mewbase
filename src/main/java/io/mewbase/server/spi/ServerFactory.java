package io.mewbase.server.spi;

import io.mewbase.server.Server;
import io.mewbase.server.ServerOptions;
import io.vertx.core.Vertx;
import io.vertx.core.spi.cluster.ClusterManager;

/**
 * Created by tim on 29/10/16.
 */
public interface ServerFactory {

    Server newServer(ServerOptions serverOptions);

    Server newServer(Vertx vertx, ServerOptions serverOptions);

    Server newServer(Vertx vertx, ClusterManager clusterManager, ServerOptions serverOptions);
}
