package file.receiver;

import config.Config;
import lombok.Getter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Getter
class Attachment {
    private ByteBuffer buffer;
    private long readPosition;
    private AsynchronousFileChannel fileChannel;
    private String fileName;
    private long fileSize;

    Attachment() {
        readPosition = 0;
    }

    public void calcFileData(String string) {
        System.out.println("@ " + string);
        String temp[] = string.replace(Config.END_MESSAGE_MARKER, "").split(Config.MESSAGE_DELIMITTER.replace("|", "\\|"));
        fileName = temp[0];
        fileSize = Long.parseLong(temp[1]);
    }

    public void addPosition(int position) {
        readPosition += position;
    }

    public void openFileChannel(Path path) throws IOException {
        fileChannel = AsynchronousFileChannel.open(
                path,
                StandardOpenOption.WRITE
        );
    }
}