package com.learn.design.pattern;


/*
* // Without Decorator — need a class for every combination
class EmailNotifier { }
class EmailWithSmsNotifier extends EmailNotifier { }
class EmailWithSlackNotifier extends EmailNotifier { }
class EmailWithSmsAndSlackNotifier extends EmailNotifier { }
class EmailWithSmsAndSlackAndLoggingNotifier extends EmailNotifier { }
// ... 2^N classes for N optional features
*
* With 4 optional features, you'd need 16 subclasses.
*  With 6 features → 64 subclasses.
* This is the combinatorial explosion Decorator solves.
*
* */

import java.time.LocalDateTime;

interface Notifier {
    void send(String recipient, String message);
}

class EmailNotifier implements Notifier{
    @Override
    public void send(String recipient, String message) {
        System.out.println("EMAIL : " + recipient + " Message : " + message);
    }
}

abstract class NotifierDecorator implements Notifier{
    protected final  Notifier notifier;
    public NotifierDecorator(Notifier notifier){
        this.notifier = notifier;
    }

    @Override
    public void send(String recipient, String message) {
        notifier.send(recipient,message);
    }
}

class SmsDecorator extends NotifierDecorator{
    private String number;
    public SmsDecorator(Notifier notifier,String number){
        super(notifier);
        this.number = number;
    }

    @Override
    public void send(String recipient, String message) {
        super.send(recipient, message);
        System.out.println("SMS : " +  number + " Message : " + message);
    }
}

class SlackDecorator extends NotifierDecorator {
    private String channel;
    public SlackDecorator(Notifier notifier, String channel){
        super(notifier);
        this.channel = channel;
    }

    @Override
    public void send(String recipient, String message) {
        super.send(recipient, message);
        System.out.println("Slack : " +  channel + " Message : " + message);
    }
}

class LoggingDecorator extends NotifierDecorator {

    public LoggingDecorator(Notifier wrapped) {
        super(wrapped);
    }

    @Override
    public void send(String recipient, String message) {
        System.out.println("LOG  → Sending notification to : " + recipient +
                " at : " + LocalDateTime.now());
        super.send(recipient, message);  // delegate after logging
        System.out.println("LOG  → Notification sent successfully");
    }
}

class RetryDecorator extends NotifierDecorator {

    private final int maxRetries;

    public RetryDecorator(Notifier wrapped, int maxRetries) {
        super(wrapped);
        this.maxRetries = maxRetries;
    }

    @Override
    public void send(String recipient, String message) {
        int attempt = 0;
        while (attempt < maxRetries) {
            try {
                super.send(recipient, message);
                return; // success — exit
            } catch (Exception e) {
                attempt++;
                System.out.println("RETRY → Attempt " + attempt + " failed: " + e.getMessage());
                if (attempt >= maxRetries) {
                    throw new RuntimeException("All " + maxRetries + " retries exhausted", e);
                }
            }
        }
    }
}

public class DecoratorPattern {
    public static void main(String[] args) {
        Notifier emailOnly = new EmailNotifier();
        emailOnly.send("ayus@email.com", "Order confirmed");

        System.out.println("-------------------");

        Notifier emailAndSms = new SmsDecorator(new EmailNotifier(), "123456789");
        emailAndSms.send("ayus@email.com", "Order shipped");

        System.out.println("-------------------");

        Notifier fullyDecorated = new LoggingDecorator(
                new RetryDecorator(
                        new SlackDecorator(
                                new SmsDecorator(
                                        new EmailNotifier(), "123456789"
                                ), "orders"
                        ), 3
                )
        );
        fullyDecorated.send("ayus@email.com", "Payment received");
    }
}
