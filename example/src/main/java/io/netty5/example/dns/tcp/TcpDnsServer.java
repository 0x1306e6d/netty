/*
 * Copyright 2021 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty5.example.dns.tcp;

import io.netty5.bootstrap.Bootstrap;
import io.netty5.bootstrap.ServerBootstrap;
import io.netty5.buffer.ByteBufUtil;
import io.netty5.channel.Channel;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.channel.ChannelInitializer;
import io.netty5.channel.IoHandlerFactory;
import io.netty5.channel.MultithreadEventLoopGroup;
import io.netty5.channel.SimpleChannelInboundHandler;
import io.netty5.channel.nio.NioHandler;
import io.netty5.channel.socket.SocketChannel;
import io.netty5.channel.socket.nio.NioServerSocketChannel;
import io.netty5.channel.socket.nio.NioSocketChannel;
import io.netty5.handler.codec.dns.DefaultDnsQuery;
import io.netty5.handler.codec.dns.DefaultDnsQuestion;
import io.netty5.handler.codec.dns.DefaultDnsRawRecord;
import io.netty5.handler.codec.dns.DefaultDnsResponse;
import io.netty5.handler.codec.dns.DnsOpCode;
import io.netty5.handler.codec.dns.DnsQuery;
import io.netty5.handler.codec.dns.DnsQuestion;
import io.netty5.handler.codec.dns.DnsRawRecord;
import io.netty5.handler.codec.dns.DnsRecord;
import io.netty5.handler.codec.dns.DnsRecordType;
import io.netty5.handler.codec.dns.DnsSection;
import io.netty5.handler.codec.dns.TcpDnsQueryDecoder;
import io.netty5.handler.codec.dns.TcpDnsQueryEncoder;
import io.netty5.handler.codec.dns.TcpDnsResponseDecoder;
import io.netty5.handler.codec.dns.TcpDnsResponseEncoder;
import io.netty5.handler.logging.LogLevel;
import io.netty5.handler.logging.LoggingHandler;
import io.netty5.util.NetUtil;

import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class TcpDnsServer {
    private static final String QUERY_DOMAIN = "www.example.com";
    private static final int DNS_SERVER_PORT = 53;
    private static final String DNS_SERVER_HOST = "127.0.0.1";
    private static final byte[] QUERY_RESULT = {(byte) 192, (byte) 168, 1, 1};

    public static void main(String[] args) throws Exception {
        IoHandlerFactory ioHandlerFactory = NioHandler.newFactory();
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(new MultithreadEventLoopGroup(1, ioHandlerFactory),
                       new MultithreadEventLoopGroup(ioHandlerFactory))
                .channel(NioServerSocketChannel.class)
                .handler(new LoggingHandler(LogLevel.INFO))
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) throws Exception {
                        ch.pipeline().addLast(new TcpDnsQueryDecoder(), new TcpDnsResponseEncoder(),
                                new SimpleChannelInboundHandler<DnsQuery>() {
                                    @Override
                                    protected void messageReceived(ChannelHandlerContext ctx,
                                                                DnsQuery msg) throws Exception {
                                        DnsQuestion question = msg.recordAt(DnsSection.QUESTION);
                                        System.out.println("Query domain: " + question);

                                        //always return 192.168.1.1
                                        ctx.writeAndFlush(newResponse(ctx, msg, question, 600, QUERY_RESULT));
                                    }

                                    private DefaultDnsResponse newResponse(ChannelHandlerContext ctx,
                                                                           DnsQuery query,
                                                                           DnsQuestion question,
                                                                           long ttl, byte[]... addresses) {
                                        DefaultDnsResponse response = new DefaultDnsResponse(query.id());
                                        response.addRecord(DnsSection.QUESTION, question);

                                        for (byte[] address : addresses) {
                                            DefaultDnsRawRecord queryAnswer = new DefaultDnsRawRecord(
                                                    question.name(),
                                                    DnsRecordType.A, ttl, ctx.bufferAllocator().copyOf(address));
                                            response.addRecord(DnsSection.ANSWER, queryAnswer);
                                        }
                                        return response;
                                    }
                                });
                    }
                });
        final Channel channel = bootstrap.bind(DNS_SERVER_PORT).get();
        Executors.newSingleThreadScheduledExecutor().schedule(new Runnable() {
            @Override
            public void run() {
                try {
                    clientQuery();
                    channel.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, 1000, TimeUnit.MILLISECONDS);
        channel.closeFuture().sync();
    }

    // copy from TcpDnsClient.java
    private static void clientQuery() throws Exception {
        MultithreadEventLoopGroup group = new MultithreadEventLoopGroup(NioHandler.newFactory());
        try {
            Bootstrap b = new Bootstrap();
            b.group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new TcpDnsQueryEncoder())
                                    .addLast(new TcpDnsResponseDecoder())
                                    .addLast(new SimpleChannelInboundHandler<DefaultDnsResponse>() {
                                        @Override
                                        protected void messageReceived(
                                                ChannelHandlerContext ctx, DefaultDnsResponse msg) {
                                            try {
                                                handleQueryResp(msg);
                                            } finally {
                                                ctx.close();
                                            }
                                        }
                                    });
                        }
                    });

            final Channel ch = b.connect(DNS_SERVER_HOST, DNS_SERVER_PORT).get();

            int randomID = new Random().nextInt(60000 - 1000) + 1000;
            DnsQuery query = new DefaultDnsQuery(randomID, DnsOpCode.QUERY)
                    .setRecord(DnsSection.QUESTION, new DefaultDnsQuestion(QUERY_DOMAIN, DnsRecordType.A));
            ch.writeAndFlush(query).sync();
            boolean success = ch.closeFuture().await(10, TimeUnit.SECONDS);
            if (!success) {
                System.err.println("dns query timeout!");
                ch.close().sync();
            }
        } finally {
            group.shutdownGracefully();
        }
    }

    private static void handleQueryResp(DefaultDnsResponse msg) {
        if (msg.count(DnsSection.QUESTION) > 0) {
            DnsQuestion question = msg.recordAt(DnsSection.QUESTION, 0);
            System.out.printf("name: %s%n", question.name());
        }
        for (int i = 0, count = msg.count(DnsSection.ANSWER); i < count; i++) {
            DnsRecord record = msg.recordAt(DnsSection.ANSWER, i);
            if (record.type() == DnsRecordType.A) {
                //just print the IP after query
                DnsRawRecord raw = (DnsRawRecord) record;
                System.out.println(NetUtil.bytesToIpAddress(ByteBufUtil.getBytes(raw.content())));
            }
        }
    }
}
