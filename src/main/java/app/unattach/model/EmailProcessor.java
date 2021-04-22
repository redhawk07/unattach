package app.unattach.model;

import app.unattach.model.attachmentstorage.UserStorage;
import app.unattach.utils.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import javax.mail.BodyPart;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeUtility;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.*;

class EmailProcessor {
  private static final Logger logger = Logger.get();

  private final UserStorage userStorage;
  private final Email email;
  private final ProcessSettings processSettings;
  private final FilenameFactory filenameFactory;
  private int fileCounter = 0;
  private final List<BodyPart> copiedBodyParts;
  private final Map<String, String> originalToNormalizedFilename;
  private BodyPart mainTextBodyPart;
  private BodyPart mainHtmlBodyPart;

  private EmailProcessor(UserStorage userStorage, Email email, ProcessSettings processSettings) {
    this.userStorage = userStorage;
    this.email = email;
    this.processSettings = processSettings;
    filenameFactory = new FilenameFactory(processSettings.filenameSchema());
    copiedBodyParts = new LinkedList<>();
    originalToNormalizedFilename = new TreeMap<>();
  }

  static Set<String> process(UserStorage userStorage, Email email, MimeMessage mimeMessage,
                             ProcessSettings processSettings)
      throws IOException, MessagingException {
    EmailProcessor processor = new EmailProcessor(userStorage, email, processSettings);
    processor.explore(mimeMessage, processor::workAroundUnsupportedContentType);
    if (processSettings.processOption().shouldDownload()) {
      processor.explore(mimeMessage, processor::saveAttachment);
    }
    if (processSettings.processOption().shouldRemove()) {
      processor.removeCopiedBodyParts();
    }
    if (processSettings.addMetadata()) {
      processor.explore(mimeMessage, processor::findTextAndHtml);
      processor.addReferencesToContent();
    }
    mimeMessage.saveChanges();
    return processor.originalToNormalizedFilename.keySet();
  }

  @FunctionalInterface
  private interface CheckedFunction<T> {
    boolean accept(T t) throws MessagingException, IOException;
  }

  private void explore(Part part, CheckedFunction<BodyPart> function) throws IOException, MessagingException {
    if (part.getContent() instanceof Multipart multipart) {
      for (int i = 0; i < multipart.getCount(); ++i) {
        BodyPart bodyPart = multipart.getBodyPart(i);
        boolean recurse = function.accept(bodyPart);
        if (recurse) {
          explore(bodyPart, function);
        }
      }
    }
  }

  /**
   * As per https://bugs.openjdk.java.net/browse/JDK-8195686, Java doesn't have direct support for iso-8859-8-i
   * encoding; however, iso-8859-8 is equivalent, so we pre-emptively replace it.
   *
   * @return Whether to recursively explore child body parts.
   */
  private boolean workAroundUnsupportedContentType(BodyPart bodyPart) throws MessagingException {
    String[] contentTypes = bodyPart.getHeader("Content-Type");
    if (contentTypes == null) {
      logger.warn("No Content-Type header found.");
      return true;
    }
    if (contentTypes.length == 1) {
      String contentType = contentTypes[0];
      if (contentType.contains("iso-8859-8-i")) {
        String newContentType = contentType.replace("iso-8859-8-i", "iso-8859-8");
        bodyPart.setHeader("Content-Type", newContentType);
      }
    }
    return true;
  }

  /**
   * Save attachment to disk if downloadable.
   *
   * @return Whether to recursively explore child body parts.
   */
  private boolean saveAttachment(BodyPart bodyPart) throws IOException, MessagingException {
    if (!processSettings.processOption().shouldProcessEmbedded() && bodyPart.isMimeType("multipart/related")) {
      return false;
    }
    String originalFilename = getFilename(bodyPart);
    if (!isDownloadable(bodyPart, originalFilename)) {
      return true;
    }
    String normalizedFilename = filenameFactory.getFilename(email, fileCounter++, originalFilename);
    try (InputStream inputStream = bodyPart.getInputStream()) {
      userStorage.saveAttachment(inputStream, processSettings.targetDirectory(), normalizedFilename, email.getTimestamp());
      copiedBodyParts.add(bodyPart);
      originalToNormalizedFilename.put(originalFilename, normalizedFilename);
      logger.info("Saved attachment %s from email with subject '%s' to file %s.", originalFilename, email.getSubject(),
          normalizedFilename);
    }
    return false;
  }

  /**
   * Based on the documentation of {@link BodyPart#getDisposition()} and https://tools.ietf.org/html/rfc2183.
   */
  private boolean isDownloadable(BodyPart bodyPart, String filename) throws MessagingException {
    String disposition = bodyPart.getDisposition();
    return (disposition == null || disposition.equals(BodyPart.ATTACHMENT)) && filename != null;
  }

  private String getFilename(BodyPart bodyPart) throws MessagingException, UnsupportedEncodingException {
    String rawFilename = bodyPart.getFileName();
    if (rawFilename == null) {
      logger.error("Empty attachment filename.");
      return null;
    }
    try {
      return MimeUtility.decodeText(rawFilename).trim();
    } catch (UnsupportedEncodingException e) {
      if (rawFilename.contains("iso-8859-8-i")) {
        rawFilename = rawFilename.replace("iso-8859-8-i", "iso-8859-8");
        return MimeUtility.decodeText(rawFilename).trim();
      }
      logger.error("Failed to decode the attachment filename: %s", e.getMessage());
      return null;
    }
  }

  private void removeCopiedBodyParts() throws MessagingException {
    for (BodyPart bodyPart : copiedBodyParts) {
      bodyPart.getParent().removeBodyPart(bodyPart);
    }
  }

  /**
   * Find text and HTML body parts.
   *
   * @return Whether to recursively explore child body parts.
   */
  private boolean findTextAndHtml(BodyPart bodyPart) throws MessagingException {
    if (bodyPart.isMimeType("text/plain") && mainTextBodyPart == null) {
      mainTextBodyPart = bodyPart;
    } else if (bodyPart.isMimeType("text/html") && mainHtmlBodyPart == null) {
      mainHtmlBodyPart = bodyPart;
    }
    return mainTextBodyPart == null || mainHtmlBodyPart == null;
  }

  private void addReferencesToContent() throws IOException, MessagingException {
    if (originalToNormalizedFilename.size() == 0) {
      return;
    }
    if (mainTextBodyPart == null && mainHtmlBodyPart == null) {
      logger.error("Failed to find either text or HTML body part to append info about removed attachments to.");
    }
    String dateTimeString = OffsetDateTime.now().toString();
    String hostname = getHostname();
    if (mainTextBodyPart != null) {
      String text = mainTextBodyPart.getContent().toString();
      String newText = generateTextSuffix(text, originalToNormalizedFilename, dateTimeString, hostname);
      mainTextBodyPart.setContent(newText, "text/plain; charset=utf-8");
    }
    if (mainHtmlBodyPart != null) {
      String html = mainHtmlBodyPart.getContent().toString();
      String newHtml = generateHtmlSuffix(html, originalToNormalizedFilename, dateTimeString, hostname);
      mainHtmlBodyPart.setContent(newHtml, "text/html; charset=utf-8");
    }
  }

  private String getHostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      return "(unknown)";
    }
  }

  private String generateTextSuffix(String text, Map<String, String> originalToNormalizedFilename,
                                    String dateTimeString, String hostname) {
    StringBuilder newText = new StringBuilder(text);
    newText.append("\n\n\n");
    newText.append("=========================================\n");
    newText.append("Previous attachments:\n");
    for (Map.Entry<String, String> entry : originalToNormalizedFilename.entrySet()) {
      String originalFilename = entry.getKey();
      String normalizedFilename = entry.getValue();
      newText.append(" - ").append(originalFilename);
      if (processSettings.processOption().shouldDownload()) {
        newText.append(" (filename: ").append(normalizedFilename).append(")");
      }
      newText.append("\n");
    }
    newText.append("\nInformation about the change:\n");
    newText.append(" - Made with:                 ").append(Constants.PRODUCT_NAME + " " +
        Constants.VERSION).append("\n");
    newText.append(" - Date and time:             ").append(dateTimeString).append("\n");
    if (processSettings.processOption().shouldDownload()) {
      newText.append(" - Download target hostname:  ").append(hostname).append("\n");
      newText.append(" - Download target directory: ").append(processSettings.targetDirectory().getAbsolutePath()).append("\n");
    }
    return newText.toString();
  }

  private String generateHtmlSuffix(String html, Map<String, String> originalToNormalizedFilename,
                                    String dateTimeString, String hostname) {
    StringBuilder suffix = new StringBuilder("<hr /><p>Previous attachments:<ul>\n");
    String targetDirectoryAbsolutePath = processSettings.targetDirectory().getAbsolutePath();
    for (Map.Entry<String, String> entry : originalToNormalizedFilename.entrySet()) {
      String originalFilename = entry.getKey();
      String normalizedFilename = entry.getValue();
      suffix.append("<li>");
      suffix.append(originalFilename);
      if (processSettings.processOption().shouldDownload()) {
        suffix.append(" (");
        Path normalisedPath = Paths.get(targetDirectoryAbsolutePath, normalizedFilename);
        suffix.append("filename: ").append(normalisedPath).append(", ");
        suffix.append("<a href='file:///").append(normalisedPath).append("'>local link</a>");
        suffix.append(")");
      }
      suffix.append("</li>\n");
    }
    suffix.append("</ul></p>\n");
    suffix.append("<p>Information about the change:<ul>\n");
    suffix.append("<li>Made with: ").append("<a href='" + Constants.HOMEPAGE + "'>" + Constants.PRODUCT_NAME + "</a> " +
        Constants.VERSION).append("</li>\n");
    suffix.append("<li>Date and time: ").append(dateTimeString).append("</li>\n");
    if (processSettings.processOption().shouldDownload()) {
      suffix.append("<li>Download target host name: ").append(hostname).append("</li>\n");
      suffix.append("<li>Download target directory: ").append(targetDirectoryAbsolutePath).append("</li>\n");
      suffix.append("<li><i>File links only work in native email apps (e.g. Mail, Outlook) on the target host.</i></li>\n");
    }
    suffix.append("</ul></p>\n");
    Document document = Jsoup.parse(html);
    document.body().append(suffix.toString());
    return document.toString();
  }
}
