package eventhandling;

import unrelibrary.gatewayapi.EventReceiver;

// this bot's own event receiver
public class QuestpressoEventReceiver extends EventReceiver {

    public QuestpressoEventReceiver() {
        this.ON_MESSAGE_CREATE = MessageCreateEventReceiver::accept;
    }
}
