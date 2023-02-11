package org.example;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.*;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.*;
import net.lingala.zip4j.ZipFile;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.apache.commons.io.FileUtils;
import java.io.*;
import java.nio.file.Paths;
import java.util.*;

public class LocalApplicationClass {
    final private File inputFile;
    final private String htmlOutputPath;
    final private String s3Path;
    final private boolean terminate;
    final private String projectBucketToString;
    private String  id;
    final private AmazonEC2 ec2Client;
    final private String sqsToManagerURL = "https://sqs.us-east-1.amazonaws.com/712064767285/LocalApplicationToManagerS3URLToDataSQS.fifo";
    final private String sqsToLocalApplicationURL = "https://sqs.us-east-1.amazonaws.com/712064767285/ManagerToLocalApplicationSQS.fifo";
    final private String managerAMIID = "ami-00c1fe75cc7de9785";
    final private AmazonSQS sqsClient;
    final private String workerRatio;
    final private AmazonS3 s3Client;
    final private int workersToInit;
    public LocalApplicationClass(String inputFilePath,String htmlOutputPath,String workerRatio, boolean terminate) throws GitAPIException, IOException {
        this.terminate = terminate;
        this.inputFile = new File(inputFilePath);
        this.workerRatio = workerRatio;
        this.htmlOutputPath = htmlOutputPath;
        this.id = UUID.randomUUID().toString();
        this.s3Path = "Input/" + id;
        this.projectBucketToString = "amazon-first-project";
        setCredentials();
        ec2Client = AmazonEC2ClientBuilder.defaultClient();
        s3Client = AmazonS3ClientBuilder.defaultClient();
        sqsClient = AmazonSQSClientBuilder.defaultClient();
        workersToInit = calculateWorkers(Integer.parseInt(workerRatio));
    }
    public int calculateWorkers(int ratio){
        int lineNumbers = 0;
        try(BufferedReader br = new BufferedReader(new FileReader(inputFile))) {
            for(String line; (line = br.readLine()) != null; ) {
                lineNumbers ++;
            }
            // line is not visible here.
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return lineNumbers/ratio;
    }
    public void startManager() {
        if (!checkIfManagerIsUp()) {
            Tag tag = new Tag("manager","manager");
            List<Tag> tags = new ArrayList<>();
            tags.add(tag);
            List<TagSpecification> tagSpecificationsList = new ArrayList<>();
            tagSpecificationsList.add(new TagSpecification().withTags(tags).withResourceType(ResourceType.Instance));
            RunInstancesRequest runRequest = new RunInstancesRequest()
                    .withImageId(managerAMIID)
                    .withInstanceType(InstanceType.T2Micro)
                    .withMaxCount(1)
                    .withMinCount(1)
                    .withTagSpecifications(tagSpecificationsList)
                    .withUserData((Base64.getEncoder().encodeToString((getUserDataScript()).getBytes())))
                    .withKeyName("keyForAMI")
                    .withMonitoring(true);
            Reservation managerReservation = new Reservation();
            managerReservation.setRequesterId("manager");
            ec2Client.runInstances(runRequest).withReservation(managerReservation);
        }
    }
    private String getUserDataScript(){
        ArrayList<String> lines = new ArrayList<String>();
        lines.add("#!/bin/bash");
        lines.add("rm Manager.jar");
        lines.add("echo Deleted Manager.jar");
        lines.add("curl -L https://github.com/nirmid/Manager/blob/master/out/artifacts/Manager_jar/Manager.jar?raw=true -o Manager.jar");
        lines.add("echo Downloading Manager.jar");
        lines.add("zip -d Manager.jar 'META-INF/.SF' 'META-INF/.RSA' 'META-INF/*SF'");
        lines.add("echo Deleting Security Issues");
        lines.add("java -jar Manager.jar");
        lines.add("echo Running Manager.jar");
        String str = Base64.getEncoder().encodeToString((join(lines, "\n").getBytes()));
        return str;
    }

    private static String join(Collection<String> s, String delimiter) {
        StringBuilder builder = new StringBuilder();
        Iterator<String> iter = s.iterator();
        while (iter.hasNext()) {
            builder.append(iter.next());
            if (!iter.hasNext()) {
                break;
            }
            builder.append(delimiter);
        }
        return builder.toString();
    }
    public boolean checkIfManagerIsUp(){
        DescribeInstancesRequest request = new DescribeInstancesRequest();
        DescribeInstancesResult response = ec2Client.describeInstances(request);
        boolean done = false;
        while (!done) {
            List<Reservation> reserveList = response.getReservations();
            for (Reservation reservation : reserveList) {
                for (Instance instance : reservation.getInstances()) {
                    if ((instance.getState().getName().equals("pending") ||instance.getState().getName().equals("running") || instance.getState().getName().equals("pending")) && instance.getTags().get(0).getValue().equals("manager")) {
                        System.out.println("Found manager!");
                        return true;
                    }
                }
            }
            request.setNextToken(response.getNextToken());
            if (response.getNextToken() == null){
                return false;
            }
        }
        return false;
    }
    public void uploadFileToS3(){
        s3Client.putObject(projectBucketToString,s3Path,inputFile);
    }
    public void sendLocalToManagerSQS(){
        Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
        messageAttributes.put("path", new MessageAttributeValue()
                .withStringValue(s3Path)
                .withDataType("String"));
        messageAttributes.put("workers", new MessageAttributeValue()
                .withStringValue(String.valueOf(workersToInit))
                .withDataType("String"));
        messageAttributes.put("id", new MessageAttributeValue()
                .withStringValue(id)
                .withDataType("String"));
        messageAttributes.put("bucket", new MessageAttributeValue()
                .withStringValue(projectBucketToString)
                .withDataType("String"));
        SendMessageRequest requestMessageSend = new SendMessageRequest()
                .withQueueUrl(sqsToManagerURL)
                .withMessageAttributes(messageAttributes)
                .withMessageDeduplicationId(s3Path)
                .withMessageGroupId(id)
                .withMessageBody(id);
        SendMessageResult result = sqsClient.sendMessage(requestMessageSend);
    }

    public Message awaitMessageFromManagerToLocalApplicationSQS() {
        while (true) {
            ReceiveMessageRequest request = new ReceiveMessageRequest()
                    .withQueueUrl(sqsToLocalApplicationURL)
                    .withMaxNumberOfMessages(1)
                    .withMessageAttributeNames("All");
            List<Message> messages = sqsClient.receiveMessage(request).getMessages();
            if (messages.size() > 0) {
                Message message = messages.get(0);

                MessageAttributeValue messageURL = message.getMessageAttributes().get(id);//{ID,URL} in messageAttributeValue hashmap.
                if (messageURL != null) {
                    return message;
                }
            }
        }
    }
    public void deleteMessage(Message message){
        sqsClient.deleteMessage(sqsToLocalApplicationURL,message.getReceiptHandle());
    }
    public void createHtml(File outputFile) throws IOException {
        BufferedWriter bw = new BufferedWriter(new FileWriter(htmlOutputPath));
        String firstLine = "<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=windows-1252\"><title>OCR</title>\n";
        String secondLine = "</head><body>\n";
        bw.write(firstLine);
        bw.write(secondLine);
        try (BufferedReader br = new BufferedReader(new FileReader(outputFile))) {
            String openingP = "<p>\n";
            String closingP = "</p>\n";
            String line;
            while ((line = br.readLine()) != null) {
                bw.write(openingP);
                bw.write("    <img src=\"" + line + "\"" + "><br>\n");
                line = br.readLine();
                bw.write("    " + line + "\n");
                bw.write(closingP);
            }
            bw.write("</body></html>\n");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        bw.close();
    }

    private void setCredentials() throws IOException, GitAPIException {
        String home = System.getProperty("user.home");
        Git.cloneRepository()
                .setURI("https://github.com/Asif857/NotCreds.git")
                .setDirectory(Paths.get(home + "/IdeaProjects/LocalApplication/src/main/creds").toFile())
                .call();
        String zipFilePath = home + "/IdeaProjects/LocalApplication/src/main/creds/aws_creds.zip";
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
        FileUtils.deleteDirectory(new File(home +"/IdeaProjects/LocalApplication/src/main/creds"));
    }

    public String getId(){
        return this.id;
    }

    public File getFileFromS3(String messageS3Path) {
        String outputPath = messageS3Path;
        System.out.println("outputpath in s3: "+outputPath);
        String home = System.getProperty("user.home");
        File outputFile = new File (home + "/IdeaProjects/LocalApplication/src/main/java/Output/outputFile.txt");
        s3Client.getObject(new GetObjectRequest(projectBucketToString,outputPath),outputFile);
        return outputFile;
    }

    public boolean getTerminate() {
        return this.terminate;
    }

    public void sendTerminate() {
        Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
        messageAttributes.put("TERMINATE", new MessageAttributeValue()
                .withStringValue("TERMINATE")
                .withDataType("String"));
        SendMessageRequest requestMessageSend = new SendMessageRequest()
                .withQueueUrl(sqsToManagerURL)
                .withMessageAttributes(messageAttributes)
                .withMessageDeduplicationId("TERMINATE")
                .withMessageGroupId("TERMINATE")
                .withMessageBody("TERMINATE");
        SendMessageResult result = sqsClient.sendMessage(requestMessageSend);
    }
}
