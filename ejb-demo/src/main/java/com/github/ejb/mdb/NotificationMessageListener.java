package com.github.ejb.mdb;

import javax.ejb.ActivationConfigProperty;
import javax.ejb.EJB;
import javax.ejb.MessageDriven;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.TextMessage;

import com.github.ejb.stateless.AuditService;

/**
 * MDB with destinationLookup and @EJB injection.
 */
@MessageDriven(activationConfig = {
    @ActivationConfigProperty(propertyName = "destinationType", propertyValue = "javax.jms.Topic"),
    @ActivationConfigProperty(propertyName = "destinationLookup", propertyValue = "java:app/jms/NotificationTopic")
})
public class NotificationMessageListener implements MessageListener {

    @EJB
    private AuditService auditService;

    @Override
    public void onMessage(Message message) {
        try {
            if (message instanceof TextMessage) {
                String text = ((TextMessage) message).getText();
                auditService.logAction("Notification received: " + text);
                processNotification(text);
            }
        } catch (Exception e) {
            throw new RuntimeException("Error processing notification", e);
        }
    }

    private void processNotification(String notification) {
        System.out.println("Processing notification: " + notification);
    }
}
