package es.ubu.batchdownloader.downloadworker.infrastructure.storage;

import io.minio.BucketExistsArgs;
import io.minio.MinioAsyncClient;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/**
 * Reutiliza firma y transporte del SDK para listar partes de una clave exacta. MinIO devuelve
 * StorageClass vacío, que el modelo Upload del SDK 8.5.17 rechaza aunque no lo necesitemos.
 */
class MinioMultipartClient extends MinioAsyncClient {
    record PendingUpload(String objectName, String uploadId) {}

    MinioMultipartClient(MinioAsyncClient client) { super(client); }

    List<PendingUpload> incomplete(String bucket, String objectKey) throws Exception {
        List<PendingUpload> uploads = new ArrayList<>();
        String keyMarker = "";
        String uploadMarker = "";
        boolean truncated;
        do {
            var query = newMultimap("uploads", "", "prefix", objectKey, "max-uploads", "1000");
            if (!keyMarker.isEmpty()) query.put("key-marker", keyMarker);
            if (!uploadMarker.isEmpty()) query.put("upload-id-marker", uploadMarker);
            try (var response = executeGetAsync(BucketExistsArgs.builder().bucket(bucket).build(),
                    null, query).get()) {
                var factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                var root = factory.newDocumentBuilder().parse(response.body().byteStream()).getDocumentElement();
                String truncatedValue = value(root, "IsTruncated");
                if (!root.getTagName().equals("ListMultipartUploadsResult")
                        || !(truncatedValue.equals("true") || truncatedValue.equals("false"))) {
                    throw new IOException("Invalid multipart inventory response");
                }
                var entries = root.getElementsByTagName("Upload");
                for (int index = 0; index < entries.getLength(); index++) {
                    Element entry = (Element) entries.item(index);
                    String key = value(entry, "Key");
                    String uploadId = value(entry, "UploadId");
                    if (!key.equals(objectKey) || uploadId.isEmpty()) {
                        throw new IOException("Unexpected multipart identity");
                    }
                    uploads.add(new PendingUpload(key, uploadId));
                }
                String nextKey = value(root, "NextKeyMarker");
                String nextUpload = value(root, "NextUploadIdMarker");
                truncated = Boolean.parseBoolean(truncatedValue);
                if (truncated && nextKey.equals(keyMarker) && nextUpload.equals(uploadMarker)) {
                    throw new IOException("Multipart pagination did not advance");
                }
                keyMarker = nextKey;
                uploadMarker = nextUpload;
            }
        } while (truncated);
        return uploads;
    }

    private static String value(Element element, String name) {
        var nodes = element.getElementsByTagName(name);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent();
    }
}
