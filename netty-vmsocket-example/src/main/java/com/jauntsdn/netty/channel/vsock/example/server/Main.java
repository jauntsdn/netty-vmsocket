/*
 * Copyright 2023 - present Maksym Ostroverkhov.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jauntsdn.netty.channel.vsock.example.server;

import com.jauntsdn.netty.channel.vsock.EpollServerVSockChannel;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.VSockAddress;
import io.netty.util.ResourceLeakDetector;
import java.io.IOException;
import java.net.SocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
  private static final Logger logger = LoggerFactory.getLogger(Main.class);

  public static void main(String[] args) throws Exception {
    ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED);

    int cid =
        Integer.parseInt(System.getProperty("HOST", String.valueOf(VSockAddress.VMADDR_CID_LOCAL)));
    int port = Integer.parseInt(System.getProperty("PORT", "8088"));

    logger.info("\n==> vm sockets load test server\n");
    logger.info("\n==> bind address: {}:{}", cid, port);

    ServerBootstrap bootstrap = new ServerBootstrap();
    Channel server =
        bootstrap
            .group(new EpollEventLoopGroup(4))
            .channel(EpollServerVSockChannel.class)
            .childHandler(new ConnectionAcceptor())
            .bind(new VSockAddress(cid, port))
            .sync()
            .channel();
    SocketAddress vsockAddress = server.localAddress();
    logger.info("\n==> Server is listening on {}", vsockAddress);
    server.closeFuture().sync();
  }

  private static class ConnectionAcceptor extends ChannelInitializer<Channel> {

    @Override
    protected void initChannel(Channel ch) {
      ch.pipeline().addLast(new Handler());
    }
  }

  private static class Handler extends ChannelDuplexHandler {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object message) {
      ByteBuf payload = (ByteBuf) message;
      int readableBytes = payload.readableBytes();
      ByteBuf frame = ctx.alloc().buffer(readableBytes);

      frame.writeBytes(payload);
      payload.release();
      ctx.write(frame, ctx.voidPromise());
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
      ctx.flush();
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
      if (!ctx.channel().isWritable()) {
        ctx.flush();
      }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      if (cause instanceof IOException) {
        return;
      }
      logger.info("Unexpected channel error", cause);
      ctx.close();
    }
  }
}
