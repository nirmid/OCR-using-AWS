package org.example;

import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.IOException;
import java.util.List;

public class Main
{
    public static void startThreads(List<Thread> threadList){
        for(int i=0; i<threadList.size(); i=i+1)
            threadList.get(i).start();
    }
    public static void stopThreads(List<Thread> threadList) throws InterruptedException {
        for(int i=0; i<threadList.size(); i=i+1){
            threadList.get(i).join();
            threadList.remove(i);
        }
    }
    public static void main( String[] args ) throws InterruptedException, GitAPIException, IOException {
        ManagerClass manager = new ManagerClass();
        S3DownloaderAndWorkerInitiliazer s3DownloaderAndWorkerInitiliazer = new S3DownloaderAndWorkerInitiliazer(manager);
        FileSplitter fileSplitter = new FileSplitter(manager);
        workerMessagesHandler workerMessagesHandler = new workerMessagesHandler(manager);
        Thread T1 = new Thread(s3DownloaderAndWorkerInitiliazer);
        Thread T2 = new Thread(fileSplitter);
        Thread T3 = new Thread(workerMessagesHandler);
        manager.addToThreadList(T1);
        manager.addToThreadList(T2);
        manager.addToThreadList(T3);
        startThreads(manager.getThreadList());
        stopThreads(manager.getThreadList());
        stopThreads(manager.getThreadList());// if we initiated new threads, we won't wait for them in the first stopThreads.

    }
}
