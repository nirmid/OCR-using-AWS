package org.example;

import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.*;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.model.*;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class workerMessagesHandler implements Runnable {
    final private String workerToManagerSQS = "https://sqs.us-east-1.amazonaws.com/712064767285/processedDataSQS.fifo";
    final private AmazonSQS sqsClient;

    final private AmazonS3 s3Client;
    final private String managerToLocalApplicationSQSURL = "https://sqs.us-east-1.amazonaws.com/712064767285/ManagerToLocalApplicationSQS.fifo";
    private ConcurrentHashMap<String,File> fileIDHashmap;
    private String uploadBucket;
    final private ManagerClass manager;
    final private AmazonEC2 ec2Client;
    private String sqsToManagerUrl;

    public workerMessagesHandler(ManagerClass manager) {
        this.ec2Client = manager.getEc2Client();
        this.manager = manager;
        this.uploadBucket = manager.getUploadBucket();
        this.s3Client = manager.getS3Client();
        this.sqsClient = manager.getSqsClient();
        this.fileIDHashmap = manager.getFileIDHashmap();
        this.sqsToManagerUrl = manager.getSqsFromLocalApplicationURL();
    }

    public List<Message> getMessagesFromWorkerSQS() throws InterruptedException {
        ReceiveMessageRequest request = new ReceiveMessageRequest()
                .withQueueUrl(workerToManagerSQS)
                .withMaxNumberOfMessages(10)
                .withMessageAttributeNames("All");
        List<Message> messages = null;
        while (messages == null) {
            messages = sqsClient.receiveMessage(request).getMessages();
            Thread.sleep(5000);
        }
        return messages;
    }

    public void deleteMessagesWorkerToManagerSQS(List<Message> messages) {
        for (Message message : messages) {
            sqsClient.deleteMessage(workerToManagerSQS, message.getReceiptHandle());
        }
    }

    public List<File> updateFiles(List<Message> messages) throws IOException {
        List<File> finishedFiles = new ArrayList<>();
        System.out.println("WMH: "+fileIDHashmap.toString());
        for (Message message: messages){
            String id = message.getMessageAttributes().get("id").getStringValue();
            String imageUrl = message.getBody();
            String imageText = message.getMessageAttributes().get("message").getStringValue();
            boolean eof = message.getMessageAttributes().get("eof").getStringValue().equals("true");
            File file = fileIDHashmap.get(id);
            writeToFile(file,imageUrl,imageText);
            if (eof){
                finishedFiles.add(file);
                System.out.println("File added to be sent");
            }
        }
        return finishedFiles;
    }

    private void writeToFile(File file,String imageUrl,String imageText) throws IOException {
        FileWriter fw = null;
        BufferedWriter bw = null;
        PrintWriter pw = null;
        try {
            fw = new FileWriter(file.getAbsoluteFile(), true);
            bw = new BufferedWriter(fw);
            pw = new PrintWriter(bw);
            pw.println(imageUrl);
            pw.println(imageText);
            pw.flush();
        } finally {
            try {
                fw.close();
                bw.close();
                pw.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
    public void uploadToS3(List<File> files){
        for (File file : files){
            System.out.println("uploading finished file to s3");
            String s3OutputPath = "Output/" + file.getName();
            s3Client.putObject(uploadBucket,s3OutputPath,file);
            fileIDHashmap.remove(file.getName().substring(0,file.getName().length()-4));
        }

    }

    public void sendOutputURLToLocalApplication(List<File> files){
        for (File file:files){
            String id = file.getName().substring(0,file.getName().length()-4);
            Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
            messageAttributes.put(id, new MessageAttributeValue()
                    .withStringValue("Output/" + file.getName())
                    .withDataType("String"));
            SendMessageRequest requestMessageSend = new SendMessageRequest()
                    .withQueueUrl(managerToLocalApplicationSQSURL)
                    .withMessageBody("Sht")
                    .withMessageAttributes(messageAttributes)
                    .withMessageDeduplicationId(id)
                    .withMessageGroupId(id);
            SendMessageResult result = sqsClient.sendMessage(requestMessageSend);
            System.out.println("sent files to Local");
        }
    }

    public void shutDownWorkers(){
        System.out.println("shutting down workers");
        DescribeInstancesRequest request = new DescribeInstancesRequest();
        DescribeInstancesResult response = ec2Client.describeInstances(request);
        Instance managerInstance = null ;
        boolean done = false;
        while (!done) {
            List<Reservation> reserveList = response.getReservations();
            for (Reservation reservation : reserveList) {
                for (Instance instance : reservation.getInstances()) {
                    if(instance.getTags().get(0).getKey().equals("worker"))
                        terminateInstance(instance);
                    else if(instance.getTags().get(0).getKey().equals("manager"))
                        managerInstance = instance;
                }

            }
            request.setNextToken(response.getNextToken());
            if (response.getNextToken() == null) {
                done = true;
            }
        }
        System.out.println("terminating manager");
        terminateInstance(managerInstance);
    }

    private void terminateInstance(Instance instance) {
        String instanceId = instance.getInstanceId();
        TerminateInstancesRequest terminateInstancesRequest = new TerminateInstancesRequest()
                .withInstanceIds(instanceId);
        ec2Client.terminateInstances(terminateInstancesRequest)
                .getTerminatingInstances()
                .get(0)
                .getPreviousState()
                .getName();
    }


    public void run() {
        while(!manager.isTerminated() || !manager.getFileIDHashmap().isEmpty()){
            List<Message> messages = null;
            try {
                messages = getMessagesFromWorkerSQS();
                List<File> filesToUpload = updateFiles(messages);
                uploadToS3(filesToUpload);
                sendOutputURLToLocalApplication(filesToUpload);
                deleteMessagesWorkerToManagerSQS(messages);
            }catch(Exception e){
                e.printStackTrace();
            }
        }
        PurgeQueueRequest request = new PurgeQueueRequest().withQueueUrl(sqsToManagerUrl);
        sqsClient.purgeQueue(request);
        System.out.println("workerMessagesHandler Terminated and purged SQS");
        shutDownWorkers();


    }
}
