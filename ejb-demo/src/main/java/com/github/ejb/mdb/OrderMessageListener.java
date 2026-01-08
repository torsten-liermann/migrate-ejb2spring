package com.github.ejb.mdb;

import javax.ejb.ActivationConfigProperty;
import javax.ejb.MessageDriven;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.TextMessage;

/**
 * Message-Driven Bean demonstrating:
 * - @MessageDriven annotation
 * - ActivationConfigProperty for destination
 * - MessageListener interface
 *
 * Should be migrated to @Component with @JmsListener.
 */
@MessageDriven(activationConfig = {
    @ActivationConfigProperty(propertyName = "destinationType", propertyValue = "javax.jms.Queue"),
    @ActivationConfigProperty(propertyName = "destination", propertyValue = "java:app/jms/OrderQueue")
})
public class OrderMessageListener implements MessageListener {

    @Override
    public void onMessage(Message message) {
        try {
            if (message instanceof TextMessage) {
                String text = ((TextMessage) message).getText();
                processOrder(text);
            }
        } catch (Exception e) {
            throw new RuntimeException("Error processing message", e);
        }
    }

    private void processOrder(String orderData) {
        System.out.println("Processing order: " + orderData);
    }
}
