package io.oss.file.service;

import io.netty.channel.ChannelFuture;
import io.oss.file.upload.UploadProgress;
import io.oss.framework.remoting.FileClient;
import io.oss.framework.remoting.listener.SocketChannelTableInfo;
import io.oss.framework.remoting.protocol.FileCommand;
import io.oss.framework.remoting.protocol.FileCommandFactory;
import io.oss.util.exception.UploadResetException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;

public class FileUploadHelper {
    private Logger logger = LoggerFactory.getLogger(FileUploadHelper.class);

    private final FileClient fileClient;

    private final File file;

    private Long position;

    /**
     * 每个分配大小 bytes
     */
    private final Integer sliceSize;

    private ByteBuffer fileSliceBuffer;

    private final RandomAccessFile randomAccessFile;

    private InetSocketAddress diskServerAddr;

    private UploadProgress uploadProgress;


    public FileUploadHelper(FileClient fileClient, String path, Integer sliceSize, InetSocketAddress diskServerAddr) {
        this.fileClient = fileClient;
        this.file = new File(path);
        this.sliceSize = sliceSize;
        this.diskServerAddr = diskServerAddr;
        this.uploadProgress = new UploadProgress(file.length());
        try {
            this.randomAccessFile = new RandomAccessFile(file, "rw");
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e.getMessage());
        }

    }

    private void requestUpload() throws InterruptedException {
        FileCommand fileCommand = fileClient.sendCommandSync(FileCommandFactory.RequestFactory
                .uploadStartRequest(file.getName(), file.length()));
        //获取初始位点
        this.position = fileCommand.getFileData().getUploadPosition();
        this.fileSliceBuffer = ByteBuffer.allocate(sliceSize);
    }

    private void readBuffer() throws IOException {
        FileChannel channel = randomAccessFile.getChannel();
        channel.read(fileSliceBuffer, position);
        fileSliceBuffer.flip();
    }

    public void upload() throws InterruptedException, IOException {
        //小于sliceSize的文件 重新分配buffer
        reallocateIfNecessary();
        readBuffer();
        FileCommand fileCommand = FileCommandFactory.RequestFactory
                .uploadFileRequest(file.getName(), file.length(), fileSliceBuffer);
        FileCommand resp = fileClient.sendCommandSync(fileCommand);

        this.position = resp.getFileData().getUploadPosition();

        if (position == file.length()) {
            //关闭连接
            Map<String, ChannelFuture> channelFutureTable = SocketChannelTableInfo.getChannelFutureTable();
            ChannelFuture channelFuture = channelFutureTable.get(diskServerAddr.toString());
            channelFuture.channel().close();
            try {
                closeFD();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        fileSliceBuffer.clear();
    }


    public void startUpload() {
        try {

            //上传前的准备，获取文件offset
            requestUpload();

            //发现服务器上已传输完毕
            if (continueUploadCheck()) {
                uploadProgress.finish();
                return;
            }

            //顺序分片传输文件
            for (int i = 0; i < calculateSlice(); i++) {
                upload();
                uploadProgress.uploadedRecord(sliceSize);
            }

        } catch (UploadResetException resetException) {
            //异常开始重传
            startUpload();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    public void closeFD() throws IOException {
        randomAccessFile.close();
    }

    private boolean continueUploadCheck() {
        if (file.length() == position) {
            logger.info("已上传完毕");
            return true;
        }
        return false;
    }

    private void reallocateIfNecessary() {
        if (file.length() - position < sliceSize) {
            fileSliceBuffer = ByteBuffer.allocate((int) (file.length() - position));
        }
    }

    private Long calculateSlice() {
        //计算分片数
        long sliceNum = (file.length() - position) / sliceSize;
        if ((file.length() % sliceSize) != 0) {
            sliceNum = sliceNum + 1;
        }
        return sliceNum;
    }

    public UploadProgress getUploadProgress(){
        return this.uploadProgress;
    }

}
