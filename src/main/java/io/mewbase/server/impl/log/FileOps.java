package io.mewbase.server.impl.log;


import io.mewbase.client.MewException;
import io.mewbase.server.ServerOptions;
import io.vertx.core.buffer.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import static io.mewbase.server.impl.log.HeaderOps.readHeader;
import static io.mewbase.server.impl.log.HeaderOps.HeaderDetails;


/**
 * FileOps provides utility methods over the log files and filesystem.
 *
 * Provides the ability to lookup FileCoords i.e. pointers into the Log files at the record level
 * It subsumes and refactors some of the common aspects of LogImpl and LogReadStream with the
 * 'primary' level of input being record numbers and the primary outputs being FileCoords.
 *
 * Subsequent refactorings should consider using Java 7/8 Paths etc.
*/

public class FileOps {

    private final static Logger logger = LoggerFactory.getLogger(FileOps.class);

    private static final int MAX_CREATE_BUFF_SIZE = 10 * 1024 * 1024;

    /**
     * Get a FileSystem handle to a given log file
     * @param logsDir : The directory for the logs
     * @param channel : The channel name
     * @param fileNumber : The log file number
     * @return A file handle or null
     */
    public static File getFile(String logsDir, String channel,int fileNumber) {
        return new File(logsDir, getFileName(channel, fileNumber));
    }

    /**
     * Given a channel and file number make a zero padded unique file name for a log file.
     * @param channel : The channel name
     * @param fileNumber : The file index
     * @return the zero padded file name
     */
    public static String getFileName(String channel, int fileNumber) {
        return channel + "-" + String.format("%012d", fileNumber) + ".log";
    }

    /**
     * Check that the files for this channel are in good order and return the
     * number of the most recently written (highest number) log file.
     * @param options
     * @param channel
     * @return
     */
    public static int checkAndGetLastLogFile(ServerOptions options, String channel) {

        Map<Integer, File> fileMap = new HashMap<>();
        File logDir = new File(options.getLogsDir());
        File[] files = logDir.listFiles(file -> {
            String name = file.getName();
            int lpos = name.lastIndexOf("-");
            if (lpos == -1) {
                logger.warn("Unexpected file in log dir: " + file);
                return false;
            } else {
                String chName = name.substring(0, lpos);
                int num = Integer.valueOf(name.substring(lpos + 1, name.length() - 4));
                boolean matches = chName.equals(channel);
                if (matches) {
                    fileMap.put(num, file);
                }
                return matches;
            }
        });
        if (files == null) {
            throw new MewException("Failed to list files in dir " + logDir.toString());
        }

        Arrays.sort(files, Comparator.naturalOrder());

        // All files before the head file must be right size
        for (int i = 0; i < files.length - 1; i++) {
            if (options.getMaxLogChunkSize() != files[i].length()) {
                throw new MewException("File unexpected size: " + files[i] + " i: " + i +
                        " max log chunk size " + options.getMaxLogChunkSize() + " length " + files[i].length());
            }
        }

        logger.trace("There are {} files in {} for channel {}", files.length, logDir, channel);

        // Check file names are contiguous
        for (int i = 0; i < fileMap.size(); i++) {
            if (!fileMap.containsKey(i)) {
                throw new MewException("Log files not in expected sequence, can't find " + getFileName(channel, i));
            }
        }
        // -1 is no files exists but 0 is both no files and only one file
        return Math.max(files.length - 1, 0);
    }

    /**
     * Create a new file and fill with zeros
     * @param file
     * @param size
     */
    public static void createAndFillFileBlocking(File file, int size) {
        logger.trace("Creating log file {} with size {}", file, size);
        ByteBuffer buff = ByteBuffer.allocate(MAX_CREATE_BUFF_SIZE);
        try (RandomAccessFile rf = new RandomAccessFile(file, "rw")) {
            FileChannel ch = rf.getChannel();
            int pos = 0;
            // We fill the file in chunks in case it is v. big - we don't want to allocate a huge byte buffer
            while (pos < size) {
                int writeSize = Math.min(MAX_CREATE_BUFF_SIZE, size - pos);
                buff.limit(writeSize);
                buff.position(0);
                ch.position(pos);
                ch.write(buff);
                pos += writeSize;
            }
            ch.force(true);
            ch.position(0);
            ch.close();
        } catch (Exception e) {
            throw new MewException("Failed to create log file", e);
        }
        logger.trace("Created log file {}", file);
    }



    /**
     * Get the coordinates of the last record in the given log file
     * @param logsDir : Logs Directory
     * @param channel : Events Channel name
     * @param fileNumber : The file number of the given file (normally the head or last file in the channel)
     * @return The File coords of the record.
     */
    public static FileCoord getCoordOfLastRecord(String logsDir, String channel, int fileNumber) {

        Path filePath = Paths.get(logsDir, getFileName(channel,fileNumber));

        if (!Files.exists(filePath)) {
            return new FileCoord(0,0L,0);
        }

        // set up a nop reply
        FileCoord coord = new FileCoord( fileNumber, 0, 0);

        // SeekableByteChannel is AutoCloseable so we can try-with-resources
        try (SeekableByteChannel sbc = Files.newByteChannel(filePath, StandardOpenOption.READ)) {

            ByteBuffer headerBuffer = ByteBuffer.allocate(HeaderOps.HEADER_SIZE);
            sbc.read(headerBuffer);
            HeaderDetails hdrDetails = readHeader( Buffer.buffer(headerBuffer.array()) );
            final long recordNumber = hdrDetails.getRecordNumber();

            int previousFileOffset = HeaderOps.HEADER_SIZE;
            coord = new FileCoord( fileNumber, (recordNumber - 1), previousFileOffset);

            // now read records until there are no more - there must be at least one record
            while ( skipRecordInChannel(sbc) != 0) {
                coord = new FileCoord(coord.fileNumber,coord.recordNumber + 1, previousFileOffset);
                previousFileOffset = (int)sbc.position();
            }
        }
        catch (Exception exp) {
            logger.error("Error seeking Log file for most recent record",exp);
        }
        return coord;
    }


    /**
     * Find the Coordinates for a specific record given its record number
     *
     * If the record number is less than or equal to the start record then start from the
     * start of the stream.
     *
     * If the record number is in the stream then return its coordinates.
     *
     * If the record number is larger than the largest record number in the stream then return
     * the coords of the last record in the stream.
     *
     * @param logsDir : Where the logs for this channel are stored
     * @param channel : The name of this channel
     * @param recordNumber : The Record number tha is being searched for
     * @return
     */
    public static FileCoord getCoordOfRecord(String logsDir, String channel, long recordNumber) {

        FileCoord coord = new FileCoord(0,0, HeaderOps.HEADER_SIZE);
        if (recordNumber > 0) {
            int fileNum = 0;
            boolean endOfFiles = false;
            while (!endOfFiles) {
                FileCoord nextCoord = findRecordInFile(logsDir,channel,fileNum,recordNumber);
                if ( nextCoord.isValidRecordPosition() ) {
                    coord = nextCoord;
                    if (coord.recordNumber == recordNumber) return coord;
                    ++fileNum;
                } else {
                    endOfFiles = true;
                }
            }
        }
        return coord;
    }


    /**
     * @param logsDir
     * @param channel
     * @param fileNumber
     * @param recordNumber
     * @return
     */
    private static FileCoord findRecordInFile(String logsDir, String channel, int fileNumber, long recordNumber) {
        Path filePath = Paths.get(logsDir, getFileName(channel, fileNumber));

        // No op coords marks end of stream
        FileCoord coord = new FileCoord(0, 0, 0);

        // we have come to the end of the log files
        if (!Files.exists(filePath)) {
            return coord;
        }

        // read this valid file
        try (SeekableByteChannel sbc = Files.newByteChannel(filePath, StandardOpenOption.READ)) {
            ByteBuffer headerBuffer = ByteBuffer.allocate(HeaderOps.HEADER_SIZE);
            sbc.read(headerBuffer);
            HeaderDetails hdrDetails = readHeader( Buffer.buffer(headerBuffer.array()) );
            final long headerRecordNumber = hdrDetails.getRecordNumber();

            // indexes and offsets.
            int previousFileOffset = HeaderOps.HEADER_SIZE;
            coord = new FileCoord( fileNumber, (headerRecordNumber - 1), previousFileOffset);

            // now read records until there are no more or we hit the target recordNumber.
            while ( skipRecordInChannel(sbc) != 0 ) {
                coord = new FileCoord(coord.fileNumber,coord.recordNumber + 1, previousFileOffset);
                previousFileOffset = (int)sbc.position();
                if ( coord.recordNumber == recordNumber) return coord;
            }
        } catch (Exception exp) {
            logger.error("Error finding record "+recordNumber+" in file "+filePath, exp);
        }
        return coord;
    }

    /**
     * Skip a single record in this byte channel
     * @param sbc : The byte stream to read
     * @return : The new position in the stream if valid or 0 if we have hit the end of the channel.
     * @throws IOException
     */
    private static long skipRecordInChannel(SeekableByteChannel sbc) throws IOException {

        long recordStartPos = sbc.position();

        // check that there is enough of the file left to read a valid header
        long bytesLeftInStream = sbc.size() - recordStartPos;

        if ( bytesLeftInStream < FramingOps.HEADER_SIZE) { return 0L; }

        ByteBuffer headerBuffer = ByteBuffer.allocate(FramingOps.HEADER_SIZE);
        sbc.read(headerBuffer);
        long recordSize = Buffer.buffer(headerBuffer.array()).getIntLE(FramingOps.CHECKSUM_SIZE);

        if (recordSize == 0) {  return 0L; } // no more records in file just 00000's

        // move the sbc read position to the start of the next record (or end possibly EOF)
        long nextRecordPosition = recordStartPos + FramingOps.FRAME_SIZE + recordSize;
        sbc.position(nextRecordPosition);
        return sbc.position();
    }


    
    static final class FileCoord {
        final int fileNumber;    // the number of the file that contains the record
        final long recordNumber; // the number of the record
        final int filePos;       // the position in the file of the record

        public FileCoord(int fileNumber, long recordNumber, int filePos) {
            this.fileNumber = fileNumber;
            this.recordNumber = recordNumber;
            this.filePos = filePos;
        }

        public boolean isValidRecordPosition() {return filePos > 0;}
    }

}
