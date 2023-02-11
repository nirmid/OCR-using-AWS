package org.example;

import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.model.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingDeque;

public class FileSplitter implements Runnable {

    final private ManagerClass manager;
    final private String managerToWorkerSQSURL = "https://sqs.us-east-1.amazonaws.com/712064767285/managerToWorkerSQS.fifo";
    final private BlockingDeque<File> filesToSplitDeque;
    final private AmazonSQS sqsClient;
    public FileSplitter(ManagerClass manager){
        this.manager = manager;
        this.filesToSplitDeque = manager.getFilesToSplitDeque();
        this.sqsClient = manager.getSqsClient();

    }
    public void splitFileAndSendToManagerToWorkerSQS(File currFile){
        try (BufferedReader br = new BufferedReader(new FileReader(currFile))) {
            List<SendMessageBatchRequestEntry> batchEntriesList = new ArrayList<>();
            String next, line = br.readLine();
            boolean eof = false;
            while (line != null) {
                next = br.readLine();
                if (next == null){
                    eof = true;
                }
                String id = currFile.getName().substring(0,currFile.getName().length()-4);
                SendMessageBatchRequestEntry entry = createBatchRequestEntry(id,line, String.valueOf(batchEntriesList.size()),eof);
                batchEntriesList.add(entry);
                if (batchEntriesList.size() == 10){
                    sendBatch(batchEntriesList);
                    batchEntriesList.clear();
                }
                line = next;
            }
            if(batchEntriesList.size() != 0){
                sendBatch(batchEntriesList);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    public void deleteLocalFile(File currFile){
        currFile.delete();
    }
    private SendMessageBatchRequestEntry createBatchRequestEntry(String localAppId,String imageUrl,String entryId, boolean eof){
        Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
        messageAttributes.put("id", new MessageAttributeValue()
                .withStringValue(localAppId)
                .withDataType("String"));
        messageAttributes.put("imageurl", new MessageAttributeValue()
                .withStringValue(imageUrl)
                .withDataType("String"));
        messageAttributes.put("eof", new MessageAttributeValue()
                .withStringValue(String.valueOf(eof))
                .withDataType("String"));
        return new SendMessageBatchRequestEntry(entryId,imageUrl)
                .withMessageAttributes(messageAttributes)
                .withMessageGroupId(localAppId)
                .withMessageDeduplicationId(imageUrl+localAppId)
                .withMessageBody("musst");
    }
    private void sendBatch(List<SendMessageBatchRequestEntry> batchEntriesList){
        SendMessageBatchRequest batchRequest = new SendMessageBatchRequest()
                .withQueueUrl(managerToWorkerSQSURL);
        batchRequest.setEntries(batchEntriesList);
        SendMessageBatchResult result = sqsClient.sendMessageBatch(batchRequest);

    }
    public void run() {
        while(!manager.isTerminated() || filesToSplitDeque.size() != 0) {
            if (filesToSplitDeque.size() > 0) {
                File file = filesToSplitDeque.removeFirst();
                splitFileAndSendToManagerToWorkerSQS(file);
                deleteLocalFile(file);
            }
        }
        System.out.println("FileSplitter Terminated");

    }



}
