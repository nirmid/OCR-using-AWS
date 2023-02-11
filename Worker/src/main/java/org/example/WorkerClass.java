package org.example;

import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.*;
import com.amazonaws.services.sqs.AmazonSQS;
import net.lingala.zip4j.ZipFile;
import net.sourceforge.tess4j.Tesseract;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WorkerClass {
    private final AmazonSQS sqsClient;
    private String imagePath;
    private String imageUrl;
    private String localApplication;
    private final Tesseract tesseract;
    private String imageProcessedText;
    private String error = null;
    private String eof;
    private final String processedDataSQSUrl = "https://sqs.us-east-1.amazonaws.com/712064767285/processedDataSQS.fifo";
    private final String managerToWorkerSQSURL = "https://sqs.us-east-1.amazonaws.com/712064767285/managerToWorkerSQS.fifo";
    public WorkerClass() throws GitAPIException, IOException {
        this.tesseract = new Tesseract();
        tesseract.setDatapath("/usr/local/share/tessdata/");
        setCredentials();
        sqsClient = AmazonSQSClientBuilder.standard().build();
    }
    private void setCredentials() throws IOException, GitAPIException {
        String home = System.getProperty("user.home");
        Git.cloneRepository()
                .setURI("https://github.com/Asif857/NotCreds.git")
                .setDirectory(Paths.get(home + "/IdeaProjects/Worker/src/main/creds").toFile())
                .call();
        String zipFilePath = home + "/IdeaProjects/Worker/src/main/creds/aws_creds.zip";
        String destDir = home + "/.aws";
        unzip(zipFilePath, destDir);
        deleteDirectory();
    }
    private void unzip(String zipFilePath, String destDir) throws IOException {
        ZipFile zipFile = new ZipFile(zipFilePath);
        zipFile.setPassword("project1".toCharArray());
        zipFile.extractAll(destDir);
    }
    private void deleteDirectory() throws IOException {
        String home = System.getProperty("user.home");
        FileUtils.deleteDirectory(new File(home + "/IdeaProjects/Worker/src/main/creds"));
    }
    private void deleteImage(){
        try {
            Files.delete(Path.of(imagePath));
        } catch (IOException e) {
            System.err.println("Unable to delete "
                    + imagePath
                    + " due to...");
            e.printStackTrace();
        }
    }
    public Message getFromManagerToWorkerSQS(){
        ReceiveMessageRequest request = new ReceiveMessageRequest()
                .withQueueUrl(managerToWorkerSQSURL)
                .withMaxNumberOfMessages(1)
                .withMessageAttributeNames("All");
        List<Message> messages = sqsClient.receiveMessage(request).getMessages();
        Message message = null;
        if (messages.size() > 0){
            message = messages.get(0);
        }
        return message;
    }
    public void bringImage(Message message) throws IOException {
        String home = System.getProperty("user.home");
        updateFromMessage(message);
        String type = imageUrl.substring(imageUrl.length() - 3, imageUrl.length());
        URL url = new URL(imageUrl);
        imagePath = home + "/image." + type;
        try {
            BufferedImage img = ImageIO.read(url);
            File file = new File(imagePath);
            ImageIO.write(img, type, file);
        }catch(Exception e){
            error = imageUrl + " " + e.getMessage();
        }
    }

    public void processImage() throws Exception{
        try {
            String inlinedText = tesseract.doOCR(new File(imagePath));
            imageProcessedText = inlinedText.replaceAll("\\R", " ");
        }
        catch (Exception e) {
            error = imageUrl + " failed because: " + e.getMessage();
            System.out.println(error);
        }
    }
    public void sendToManager(){
        String messageValue = imageProcessedText;
        if (error != null){
            messageValue = error;
        }
        Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
        System.out.println("EOF message value sent: "+eof);
        messageAttributes.put("message", new MessageAttributeValue()
                .withStringValue(messageValue)
                .withDataType("String"));
        messageAttributes.put("eof", new MessageAttributeValue()
                .withStringValue(eof)
                .withDataType("String"));
        messageAttributes.put("id", new MessageAttributeValue()
                .withStringValue(localApplication)
                .withDataType("String"));
        SendMessageRequest requestMessageSend = new SendMessageRequest()
                .withQueueUrl(processedDataSQSUrl)
                .withMessageBody(imageUrl)
                .withMessageAttributes(messageAttributes)
                .withMessageDeduplicationId(imageUrl+localApplication)
                .withMessageGroupId(localApplication);
        SendMessageResult result = sqsClient.sendMessage(requestMessageSend);
        deleteImage();
        error = null;
    }



    public void deleteMessage(Message message){
        sqsClient.deleteMessage(managerToWorkerSQSURL,message.getReceiptHandle());

    }

    public void updateFromMessage(Message message) {
        imageUrl = message.getMessageAttributes().get("imageurl").getStringValue();
        localApplication = message.getMessageAttributes().get("id").getStringValue();
        eof = message.getMessageAttributes().get("eof").getStringValue();
        System.out.println("eof value: "+eof);
    }
}
