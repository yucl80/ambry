package com.github.ambry.shared;


import com.github.ambry.clustermap.ClusterMap;
import com.github.ambry.network.RequestResponseChannel;
import com.github.ambry.utils.Utils;
import com.github.ambry.messageformat.BlobProperties;
import com.github.ambry.messageformat.BlobPropertySerDe;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

/**
 * A Put Request used to put a blob
 */
public class PutRequest extends RequestOrResponse {

  private ByteBuffer usermetadata;
  private InputStream data;
  private BlobId blobId;
  private long sentBytes = 0;
  private BlobProperties properties;

  private static final int UserMetadata_Size_InBytes = 4;


  public PutRequest(int correlationId, String clientId,
                    BlobId blobId, ByteBuffer usermetadata,
                    InputStream data, BlobProperties properties) {
    super(RequestResponseType.PutRequest, Request_Response_Version, correlationId, clientId);

    this.blobId = blobId;
    this.usermetadata = usermetadata;
    this.data = data;
    this.properties = properties;
  }

  public static PutRequest readFrom(DataInputStream stream, ClusterMap map) throws IOException {
    RequestResponseType type = RequestResponseType.PutRequest;
    short versionId  = stream.readShort();
    int correlationId = stream.readInt();
    String clientId = Utils.readIntString(stream);
    BlobId id = new BlobId(stream, map);
    BlobProperties properties = BlobPropertySerDe.getBlobPropertyFromStream(stream);
    ByteBuffer metadata = Utils.readIntBuffer(stream);
    InputStream data = stream;
    // ignore version for now
    return new PutRequest(correlationId, clientId, id, metadata, data, properties);
  }

  public BlobId getBlobId() {
    return blobId;
  }

  public ByteBuffer getUsermetadata() {
    return usermetadata;
  }

  public InputStream getData() {
    return data;
  }

  public long getDataSize() {
    return properties.getBlobSize();
  }

  public BlobProperties getBlobProperties() {
    return properties;
  }

  @Override
  public long sizeInBytes() {
    // sizeExcludingData + blob size
    return  sizeExcludingData() + properties.getBlobSize();
  }

  private int sizeExcludingData() {
    // header + partitionId + blobId size + blobId + metadata size + metadata + blob property size
    return  (int)super.sizeInBytes() + blobId.sizeInBytes() +
            UserMetadata_Size_InBytes + usermetadata.capacity() +
            BlobPropertySerDe.getBlobPropertySize(properties);
  }

  @Override
  public void writeTo(WritableByteChannel channel) throws IOException {
    if (bufferToSend == null) {
      bufferToSend = ByteBuffer.allocate(sizeExcludingData());
      writeHeader();
      bufferToSend.put(blobId.toBytes());
      BlobPropertySerDe.putBlobPropertyToBuffer(bufferToSend, properties);
      bufferToSend.putInt(usermetadata.capacity());
      bufferToSend.put(usermetadata);
      bufferToSend.flip();
    }
    while (sentBytes < sizeInBytes()) {
      if (bufferToSend.remaining() > 0) {
        int toWrite = bufferToSend.remaining();
        int written = channel.write(bufferToSend);
        sentBytes += written;
        if (toWrite != written || sentBytes == sizeInBytes()) {
          break;
        }
      }
      logger.trace("sent Bytes from Put Request {}", sentBytes);
      bufferToSend.clear();
      int dataRead = data.read(bufferToSend.array(), 0, (int)Math.min(bufferToSend.capacity(), (sizeInBytes() - sentBytes)));
      bufferToSend.limit(dataRead);
    }
  }

  @Override
  public boolean isSendComplete() {
    return sizeInBytes() == sentBytes;
  }
}