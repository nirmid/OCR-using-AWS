package org.example;

import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.*;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;

import java.io.*;
import java.util.*;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.Math.max;

public class S3DownloaderAndWorkerInitiliazer implements Runnable{
    private AmazonSQS sqsClient;
    final private int maximumWorkers = 18;
    private AmazonS3 s3Client;
    private ManagerClass manager;
    private String sqsToManagerUrl;
    private AmazonEC2 ec2Client;
    private int workersNeededInGeneral;
    public S3DownloaderAndWorkerInitiliazer(ManagerClass manager){
        this.manager = manager;
        this.workersNeededInGeneral = 0;
        this.sqsClient = manager.getSqsClient();
        this.sqsToManagerUrl = manager.getSqsFromLocalApplicationURL();
        this.s3Client = manager.getS3Client();
        this.ec2Client = manager.getEc2Client();
    }
    public void run() {
        while(!manager.isTerminated()) {
            try {
                List<Message> messages = getMessagesFromSQS();
                downloadFromS3(messages);
                if(!manager.isTerminated()) {
                    createOutputFiles(messages);
                    for (Message message : messages) {
                        int numOfWorkersNeeded = Integer.parseInt(message.getMessageAttributes().get("workers").getStringValue());
                        workersNeededInGeneral = max(numOfWorkersNeeded,workersNeededInGeneral);
                        initWorkers(numOfWorkersNeeded);
                }
                    insertToFilesToSplitDeque(messages);
                    deleteMessagesFromToManagerSQS(messages);
                    int workersNeeded = workersNeededInGeneral - countWorkers();
                    if (workersNeeded > 0){
                        initWorkers(workersNeeded);
                    }
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        System.out.println("S3DownloaderAndWorkerInitiliazer Terminated");
    }

    private void createOutputFiles(List<Message> messages) {
        ConcurrentHashMap<String,File> fileIDHashmap = manager.getFileIDHashmap();
        for(Message message : messages){
            String id = message.getMessageAttributes().get("id").getStringValue();
            try {
                File outputFile = new File(id + ".txt");
                if (outputFile.createNewFile()) {
                    System.out.println("File created: " + outputFile.getName());
                    fileIDHashmap.put(id,outputFile);
                } else
                    System.out.println("File already exists.");

            }catch(Exception e){
                System.out.println("An error occurred.");
                e.printStackTrace();
            }
        }

    }

    public List<Message> getMessagesFromSQS() throws InterruptedException {
        ReceiveMessageRequest request = new ReceiveMessageRequest()
                .withQueueUrl(sqsToManagerUrl)
                .withMaxNumberOfMessages(10)
                .withMessageAttributeNames("All");
        List<Message> messages = null;
        while (messages == null) {
            messages = sqsClient.receiveMessage(request).getMessages();
            Thread.sleep(5000);
        }
        return messages;
    }
    public void downloadFromS3(List<Message> messages){
        String home = System.getProperty("user.home");
        for (Message message : messages){
            if (message.getMessageAttributes().get("TERMINATE") != null){
                this.manager.setTerminated(true);
                break;
            }
            else {
                String messageS3Path = message.getMessageAttributes().get("path").getStringValue();
                String id = message.getMessageAttributes().get("id").getStringValue();
                String bucket = message.getMessageAttributes().get("bucket").getStringValue();
                File outputFile = new File(home + "/IdeaProjects/Manager/src/main/java/Output/" + id + ".txt");
                s3Client.getObject(new GetObjectRequest(bucket, messageS3Path), outputFile);
            }
        }
    }
    public void initWorkers(int numOfWorkersToRun){
        int currentWorkers = countWorkers();
        int numOfWorkersAllowedToAdd = maximumWorkers - currentWorkers;
        int numOfWorkersNeededToAdd = numOfWorkersToRun - currentWorkers;
        int numOfWorkersToInit = Math.min(numOfWorkersAllowedToAdd,numOfWorkersNeededToAdd);
        System.out.println("Number of workers to init: "+numOfWorkersToInit);
        if(numOfWorkersToInit > 0){
            Tag tag = new Tag("worker","worker");
            List<Tag> tags = new ArrayList<>();
            tags.add(tag);
            List<TagSpecification> tagSpecificationsList = new ArrayList<>();
            tagSpecificationsList.add(new TagSpecification().withTags(tags).withResourceType(ResourceType.Instance));
            RunInstancesRequest runRequest = new RunInstancesRequest()
                    .withImageId(manager.getWorkerAmiId())
                    .withInstanceType(InstanceType.T2Micro)
                    .withMaxCount(numOfWorkersToInit)
                    .withMinCount(numOfWorkersToInit)
                    .withTagSpecifications(tagSpecificationsList)
                    .withUserData((Base64.getEncoder().encodeToString((getUserDataScript()).getBytes())))
                    .withMonitoring(true)
                    .withInstanceInitiatedShutdownBehavior(ShutdownBehavior.Terminate);
            RunInstancesResult result = ec2Client.runInstances(runRequest);
        }
        initWorkerMessagesHandlerThreads();
    }
    private String getUserDataScript(){
        ArrayList<String> lines = new ArrayList<String>();
        lines.add("#!/bin/bash");
        lines.add("rm Worker.jar");
        lines.add("echo Deleted Worker.jar");
        lines.add("curl -L https://github.com/Asif857/Worker/blob/master/out/artifacts/Worker_jar/Worker.jar?raw=true -o Worker.jar");
        lines.add("echo Downloading Worker.jar");
        lines.add("zip -d Worker.jar 'META-INF/.SF' 'META-INF/.RSA' 'META-INF/*SF'");
        lines.add("echo Deleting Security Issues");
        //lines.add("sudo -i");
        lines.add("echo Adding root privileges");
        //lines.add("export TESSDATA_PREFIX=usr/local/lib");
        lines.add("sudo ldconfig");
        lines.add("sudo java -jar Worker.jar");
        //export LD_LIBRARY_PATH=$LD_LIBRARY_PATH:usr/local/lib
       // lines.add("echo Setting Libraries");
      //  lines.add("java -jar Worker.jar");
        lines.add("echo Running Worker.jar");
        lines.add("shutdown now");
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

    public void initWorkerMessagesHandlerThreads(){
        int currNumOfWorkerThreads = manager.getThreadList().size() - 2;
        double currWorkersNum = countWorkers();
        double workerToThreadRatio = manager.getWorkerToThreadRatio();
        int neededThreads = (int) Math.ceil(currWorkersNum/workerToThreadRatio);
        int threadsToInit = neededThreads - currNumOfWorkerThreads;
        List<Thread> threadsList= manager.getThreadList();
        for (int i = 0; i < threadsToInit ; i++){
            Thread t = new Thread(new workerMessagesHandler(manager));
            threadsList.add(t);
            t.start();
        }
    }
    private int countWorkers(){
        int currentWorkers = 0;
        DescribeInstancesRequest request = new DescribeInstancesRequest();
        DescribeInstancesResult response = ec2Client.describeInstances(request);
        boolean done = false;
        while (!done) {
            List<Reservation> reserveList = response.getReservations();
            for (Reservation reservation : reserveList) {
                    for (Instance instance : reservation.getInstances()) {
                        if ((instance.getState().getName().equals("pending") || instance.getState().getName().equals("running") || instance.getState().getName().equals("pending")) && instance.getTags().get(0).getKey().equals("worker")) {
                            currentWorkers++;
                        }
                    }
                request.setNextToken(response.getNextToken());
                if (response.getNextToken() == null) {
                    done = true;
                }
            }
        }
        System.out.println("Number of active workers: "+currentWorkers);
        return currentWorkers;
    }
    public void insertToFilesToSplitDeque(List<Message> messages){
        String home = System.getProperty("user.home");
        BlockingDeque<File> filesToSplit = manager.getFilesToSplitDeque();
        for (Message message : messages) {
            String id = message.getMessageAttributes().get("id").getStringValue();
            File outputFile = new File (home + "/IdeaProjects/Manager/src/main/java/Output/" + id + ".txt");
            filesToSplit.add(outputFile);
        }
    }
    public void deleteMessagesFromToManagerSQS(List<Message> messages){
        for (Message message:messages) {
            sqsClient.deleteMessage(sqsToManagerUrl, message.getReceiptHandle());
        }
    }
}
