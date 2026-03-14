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

package com.jauntsdn.netty.channel.vsock.example.client;

import com.jauntsdn.netty.channel.vsock.EpollVSockChannel;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.VSockAddress;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.internal.ThreadLocalRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
  private static final Logger logger = LoggerFactory.getLogger(Main.class);

  public static void main(String[] args) throws Exception {
    ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED);

    int cid =
        Integer.parseInt(System.getProperty("HOST", String.valueOf(VSockAddress.VMADDR_CID_LOCAL)));
    int port = Integer.parseInt(System.getProperty("PORT", "8088"));
    int duration = Integer.parseInt(System.getProperty("DURATION", "600"));
    int frameSize = Integer.parseInt(System.getProperty("FRAME", "64"));
    int outboundFramesWindow = Integer.parseInt(System.getProperty("WINDOW", "20000"));

    logger.info("\n==> vm sockets load test client\n");
    logger.info("\n==> remote address: {}:{}", cid, port);
    logger.info("\n==> duration: {}", duration);
    logger.info("\n==> frame payload size: {}", frameSize);
    logger.info("\n==> outbound frames window: {}", outboundFramesWindow);

    List<ByteBuf> framesPayload = framesPayload(1000, frameSize);
    FrameCounters frameCounters = new FrameCounters();

    Channel channel =
        new Bootstrap()
            .group(new EpollEventLoopGroup())
            .channel(EpollVSockChannel.class)
            .handler(
                new ChannelInitializer<Channel>() {
                  @Override
                  protected void initChannel(Channel ch) {
                    ch.pipeline()
                        .addLast(
                            new BidiStreamHandler(
                                frameCounters,
                                framesPayload,
                                ThreadLocalRandom.current(),
                                outboundFramesWindow));
                  }
                })
            .connect(new VSockAddress(cid, port))
            .sync()
            .channel();

    int warmupMillis = 5000;
    logger.info("==> warming up for {} millis...", warmupMillis);
    channel
        .eventLoop()
        .schedule(
            () -> {
              logger.info("==> warm up completed");
              frameCounters.start();
              channel
                  .eventLoop()
                  .scheduleAtFixedRate(
                      new StatsReporter(frameCounters), 1000, 1000, TimeUnit.MILLISECONDS);
            },
            warmupMillis,
            TimeUnit.MILLISECONDS);

    channel.closeFuture().sync();
    logger.info("Client terminated");
  }

  private static class FrameCounters {
    private boolean isStarted;
    private long totalSize;

    public void start() {
      isStarted = true;
    }

    public void countPayload(long size) {
      if (!isStarted) {
        return;
      }

      totalSize += size;
    }

    public long totalSize() {
      long size = totalSize;
      totalSize = 0;
      return size;
    }
  }

  private static class StatsReporter implements Runnable {
    private final FrameCounters frameCounters;

    public StatsReporter(FrameCounters frameCounters) {
      this.frameCounters = frameCounters;
    }

    @Override
    public void run() {
      logger.info("total size, KBytes => {}", frameCounters.totalSize() / (float) 1000);
    }
  }

  static class BidiStreamHandler extends ChannelDuplexHandler {

    private final FrameCounters frameCounters;
    private final List<ByteBuf> dataList;
    private final Random random;
    private final int window;
    private boolean isClosed;
    private FrameWriter frameWriter;

    BidiStreamHandler(
        FrameCounters frameCounters, List<ByteBuf> dataList, Random random, int window) {
      this.frameCounters = frameCounters;
      this.dataList = dataList;
      this.random = random;
      this.window = window;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
      frameWriter = new FrameWriter(ctx, window);
      frameWriter.startWrite();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
      isClosed = true;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object message) {
      ByteBuf payload = (ByteBuf) message;
      int size = payload.readableBytes();
      frameCounters.countPayload(size);
      payload.release();
      frameWriter.tryContinueWrite(size);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
      Channel ch = ctx.channel();
      if (!ch.isWritable()) {
        ch.flush();
      }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      if (!isClosed) {
        isClosed = true;
        logger.error("Channel error", cause);
        ctx.close();
      }
    }

    class FrameWriter {
      private final ChannelHandlerContext ctx;
      private final int window;
      private int queued;

      FrameWriter(ChannelHandlerContext ctx, int window) {
        this.ctx = ctx;
        this.window = window;
      }

      void startWrite() {
        if (isClosed) {
          return;
        }
        ChannelHandlerContext c = ctx;
        while (queued < window) {
          ByteBuf payload = payload(c);
          c.write(payload, c.voidPromise());
          queued += payload.readableBytes();
        }
        c.flush();
      }

      void tryContinueWrite(int size) {
        int q = queued -= size;
        if (q <= window / 2) {
          startWrite();
        }
      }

      ByteBuf payload(ChannelHandlerContext ctx) {
        List<ByteBuf> dl = dataList;
        int dataIndex = random.nextInt(dl.size());
        ByteBuf data = dl.get(dataIndex);
        int dataSize = data.readableBytes();
        ByteBuf frame = ctx.alloc().buffer(dataSize);
        frame.writeBytes(data, 0, dataSize);
        return frame;
      }
    }
  }

  private static List<ByteBuf> framesPayload(int count, int size) {
    Random random = new Random();
    List<ByteBuf> data = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      byte[] bytes = new byte[size];
      random.nextBytes(bytes);
      data.add(Unpooled.wrappedBuffer(bytes));
    }
    return data;
  }
}
