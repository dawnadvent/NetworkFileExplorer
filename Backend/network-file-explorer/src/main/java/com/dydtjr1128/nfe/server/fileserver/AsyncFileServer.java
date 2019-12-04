package com.dydtjr1128.nfe.server.fileserver;

import com.dydtjr1128.nfe.protocol.core.NFEProtocol;
import com.dydtjr1128.nfe.server.AdminWebSocketManager;
import com.dydtjr1128.nfe.server.AsyncServer;
import com.dydtjr1128.nfe.server.Client;
import com.dydtjr1128.nfe.server.ClientManager;
import com.dydtjr1128.nfe.server.config.Config;
import com.dydtjr1128.nfe.server.model.AdminMessage;
import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xerial.snappy.Snappy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Queue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class AsyncFileServer implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(AsyncServer.class);
    private final AsynchronousServerSocketChannel assc;
    private final AsynchronousChannelGroup channelGroup;

    public AsyncFileServer() throws IOException {
        channelGroup = AsynchronousChannelGroup.withFixedThreadPool(Config.FILE_THREAD_POOL_COUNT, Executors.defaultThreadFactory());
        assc = createAsynchronousFileServerSocketChannel();
        logger.debug("[Finish server setting with " + Config.DEFAULT_THREAD_POOL_COUNT + " thread in thread pool]");
    }

    private AsynchronousServerSocketChannel createAsynchronousFileServerSocketChannel() throws IOException {
        final AsynchronousServerSocketChannel serverSocketChannel = AsynchronousServerSocketChannel.open(channelGroup);
        serverSocketChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        serverSocketChannel.bind(new InetSocketAddress(Config.ASYNC_FILE_SERVER_PORT));
        return serverSocketChannel;
    }

    @Override
    public void run() {
        assc.accept(null, new CompletionHandler<AsynchronousSocketChannel, Void>() {
            @Override
            public void completed(AsynchronousSocketChannel result, Void attachment) {
                logger.debug("[Accept new file connection]");

                if (assc.isOpen())
                    assc.accept(null, this);

                try {
                    handleNewConnection(result);
                } catch (IOException | ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }

            }

            @Override
            public void failed(Throwable exc, Void attachment) {
            }
        });
    }

    private void handleNewConnection(AsynchronousSocketChannel channel) throws IOException, ExecutionException, InterruptedException {
        channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
        ByteBuffer dataBuffer = ByteBuffer.allocate(NFEProtocol.NETWORK_FILE_BYTE);
        Future<Integer> operations = channel.read(dataBuffer);
        operations.get();
        dataBuffer.flip();
        byte action = dataBuffer.get();
        System.out.println("get1 " + dataBuffer.position() + " " + dataBuffer.limit());
        if (dataBuffer.hasRemaining())
            dataBuffer.compact();
        else
            dataBuffer.clear();
        //추가
        System.out.println("get2 " + dataBuffer.position() + " " + dataBuffer.limit());
        if (action == FileAction.FILE_RECEIVE_FROM_CLIENT) { // 고정 서버 path + filename
            logger.debug("[File receive from client]");
            readFileFromClient(channel, dataBuffer);
        } else if (action == FileAction.FILE_SEND_TO_CLIENT) { // PATH + fileanme
            logger.debug("[File send to client]");
            Client client = ClientManager.getInstance().getClientByIP((InetSocketAddress) channel.getRemoteAddress());
            TransferFileMetaData metaData;
            if (client == null) return;
            System.out.println(client.getClientURL() + " " + client.getBlockingQueue().size());
            synchronized (client.getFilePathQueue()) {
                Queue<TransferFileMetaData> queue = client.getFilePathQueue();
                System.out.println("poll");
                metaData = queue.poll();
                System.out.println("after " + metaData.getSeverPath());
            }
            if (metaData != null)
                writeFileToClient(channel, dataBuffer, metaData);
        }
    }

    public void readFileFromClient(AsynchronousSocketChannel channel, ByteBuffer dataBuffer) {
        channel.read(dataBuffer, new Attachment(), new CompletionHandler<Integer, Attachment>() {

            @Override
            public void completed(final Integer result, final Attachment readData) {
                if (result < 0) {
                    System.out.println("close!");
                    close(channel, readData.getFileChannel());
                    return;
                }
                dataBuffer.flip();
                long messageLen = dataBuffer.getLong();
                System.out.println(messageLen);
                byte[] bytes = new byte[(int) messageLen];
                dataBuffer.get(bytes, 0, (int) messageLen);
                String string = null;
                if (dataBuffer.hasRemaining())
                    dataBuffer.compact();
                else
                    dataBuffer.clear();
                try {
                    string = Snappy.uncompressString(bytes);
                    if (string.contains(Config.END_MESSAGE_MARKER)) {
                        readData.calcFileData(string);
                        Path path = Paths.get(Config.FILE_STORE_PATH + readData.getFileName());
                        if (Files.notExists(Paths.get(Config.FILE_STORE_PATH))) {
                            try {
                                Files.createDirectories(Paths.get(Config.FILE_STORE_PATH));
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                        if (Files.notExists(path) && !Files.isDirectory(path)) {

                            System.out.println(path);
                            readData.openFileChannel(path);

                            System.out.println(result + " " + dataBuffer.position() + " " + dataBuffer.limit());
                            writeToFile(channel, readData, dataBuffer);
                        } else {
                            close(channel, readData.getFileChannel());
                            System.out.println("Download err!");
                            return;
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    close(channel, readData.getFileChannel());
                }
            }

            @Override
            public void failed(final Throwable exc, final Attachment attachment) {
                close(channel, null);
                logger.debug("[Error!]" + exc.toString());
            }
        });
    }

    public void writeToFile(AsynchronousSocketChannel channel, Attachment attachment, ByteBuffer dataBuffer) {
        System.out.println(attachment.getReadPosition() + " " + dataBuffer.position() + " " + dataBuffer.limit());
        channel.read(dataBuffer, attachment, new CompletionHandler<Integer, Attachment>() {

                    @SneakyThrows
                    @Override
                    public void completed(Integer result, Attachment attachment) {
                        if (result > 0) {
                            System.out.println("before flip " + attachment.getReadPosition() + " " + result + " " + dataBuffer.position() + " " + dataBuffer.limit());
                            dataBuffer.flip();
                            System.out.println(attachment.getReadPosition() + " " + result + " " + dataBuffer.position() + " " + dataBuffer.limit());
                            try {
                                Future<Integer> operation = attachment.getFileChannel().write(dataBuffer, attachment.getReadPosition());
                                operation.get(10, TimeUnit.SECONDS);
                            } catch (Exception e) {
                                logger.error("[Download error!] : " + attachment.getFileName(), e);
                                return;
                            }
                            attachment.addPosition(result);
                            if (dataBuffer.hasRemaining()) {
                                System.out.println(attachment.getReadPosition() + "남@" + result + " " + dataBuffer.position() + " " + dataBuffer.limit());
                                dataBuffer.compact();
                                System.out.println(attachment.getReadPosition() + "남@@" + result + " " + dataBuffer.position() + " " + dataBuffer.limit());
                            } else
                                dataBuffer.clear();
                            if (attachment.getReadPosition() == attachment.getFileSize()) {
                                AdminWebSocketManager.getInstance().writeToAdminPage(new AdminMessage(AdminMessage.DOWNLOAD_SUCCESS, attachment.getFileName() + " 다운로드 성공!"));
                                logger.debug("[Download success!] : " + attachment.getFileName());
                                dataBuffer.clear();
                                System.out.println(attachment.getReadPosition() + " " + result + " " + dataBuffer.position() + " " + dataBuffer.limit());
                                try {
                                    channel.close();
                                    attachment.getFileChannel().close();
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            } else if (attachment.getReadPosition() > attachment.getFileSize()) {
                                AdminWebSocketManager.getInstance().writeToAdminPage(new AdminMessage(AdminMessage.DOWNLOAD_FAIL, attachment.getFileName() + " 다운로드 실패!"));
                                logger.debug("[Download Error!]");
                            } else {
                                System.out.println(attachment.getReadPosition() + " " + result + " " + dataBuffer.position() + " " + dataBuffer.limit());
                                System.out.println("====================================");
                                channel.read(dataBuffer, attachment, this);
                            }

                        }
                    }

                    @Override
                    public void failed(Throwable exc, Attachment attachment) {
                        AdminWebSocketManager.getInstance().writeToAdminPage(new AdminMessage(AdminMessage.DOWNLOAD_FAIL, attachment.getFileName() + " 다운로드 실패!"));
                        logger.debug("[Error!]" + exc.toString());
                    }
                }
        );

    }


    public void writeFileToClient(AsynchronousSocketChannel channel, ByteBuffer dataBuffer, TransferFileMetaData metaData) throws IOException, ExecutionException, InterruptedException {
        Path serverPath = Paths.get(metaData.getSeverPath());

        long position = 0;
        System.out.println("!@!");
        if (Files.exists(serverPath) && !Files.isDirectory(serverPath)) {
            AsynchronousFileChannel fileChannel = AsynchronousFileChannel.open(
                    serverPath,
                    StandardOpenOption.READ
            );
            long fileSize = Files.size(serverPath);
            byte[] message = Snappy.compress(serverPath.getFileName().toString() + Config.MESSAGE_DELIMITER + fileSize + Config.END_MESSAGE_MARKER);
            System.out.println("!!");
            dataBuffer.putLong(message.length);
            dataBuffer.put(message);
            dataBuffer.flip();
            System.out.println("!!2");
            Future<Integer> future = channel.write(dataBuffer);
            future.get();
            dataBuffer.clear();
            dataBuffer.limit(0);
            System.out.println("!!2");
            fileChannel.read(
                    dataBuffer, 0, new SendData(0, dataBuffer),    // null 대신 iterations 전달
                    new CompletionHandler<Integer, SendData>() {

                        @Override
                        public void completed(Integer result, SendData sendData) {
                            if (result < 0) {
                                System.err.println("비정상 종료");
                                return;
                            }
                            sendData.addPosition(result);
                            try {
                                sendData.getBuffer().flip();
                                //System.out.println("전송 " + sendData.getReadPosition() + " " + sendData.getBuffer().position() + " " + sendData.getBuffer().limit());
                                Future<Integer> operation = channel.write(sendData.getBuffer());
                                operation.get(100, TimeUnit.SECONDS);

                            } catch (Exception e) {
                                AdminWebSocketManager.getInstance().writeToAdminPage(new AdminMessage(AdminMessage.DOWNLOAD_SUCCESS, serverPath + " 업로드 실패!"));
                                e.printStackTrace();
                                return;
                            }

                            if (sendData.getReadPosition() == fileSize) {
                                logger.debug("[Upload success!] : " + metaData.getSeverPath());
                                AdminWebSocketManager.getInstance().writeToAdminPage(new AdminMessage(AdminMessage.DOWNLOAD_SUCCESS, serverPath + " 업로드 성공!"));
                                return;
                            }
                            sendData.getBuffer().clear();
                            fileChannel.read(sendData.getBuffer(), sendData.getReadPosition(), sendData, this);
                        }

                        @Override
                        public void failed(Throwable exc, SendData attachment) {
                            try {
                                channel.close();
                                fileChannel.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                            AdminWebSocketManager.getInstance().writeToAdminPage(new AdminMessage(AdminMessage.DOWNLOAD_SUCCESS, serverPath + " 업로드 실패!"));
                        }
                    });
        }
    }

    public void close(AsynchronousSocketChannel channel, AsynchronousFileChannel fileChannel) {
        try {
            if (fileChannel != null && fileChannel.isOpen())
                fileChannel.close();
            if (!channel.isOpen())
                channel.close();
        } catch (IOException e) {
            e.printStackTrace();
            System.out.println("FileServer close err!");
        }
    }

}
