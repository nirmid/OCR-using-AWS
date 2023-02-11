package org.example;
import com.amazonaws.services.sqs.model.Message;
import org.eclipse.jgit.api.errors.GitAPIException;
import java.io.File;
import java.io.IOException;

public class Main {
    public static void main(String[] args) throws GitAPIException, IOException {
        String inputPath = args[0];
        String outputPath = args[1];
        String ratioToCreateWorkers = args[2];
        Boolean terminate = Boolean.parseBoolean(args[3]);
        System.out.println("terminate status: "+ terminate);
        LocalApplicationClass localApplication = new LocalApplicationClass(inputPath,outputPath,ratioToCreateWorkers,terminate);
        localApplication.uploadFileToS3();
        localApplication.sendLocalToManagerSQS();
        localApplication.startManager();
        Message message = localApplication.awaitMessageFromManagerToLocalApplicationSQS();
        String messageS3Path = message.getMessageAttributes().get(localApplication.getId()).getStringValue();
        File outputFile = localApplication.getFileFromS3(messageS3Path);
        System.out.println("got file & messgae from manager");
        localApplication.deleteMessage(message);
        System.out.println("deleted message from manager");
        localApplication.createHtml(outputFile);
        System.out.println("created html");
        if (localApplication.getTerminate()){
            System.out.println("reached terminate");
            localApplication.sendTerminate();
        }
    }
}