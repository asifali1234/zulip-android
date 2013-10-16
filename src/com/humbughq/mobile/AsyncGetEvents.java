package com.humbughq.mobile;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.concurrent.Callable;

import org.apache.http.client.HttpResponseException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import com.j256.ormlite.dao.RuntimeExceptionDao;
import com.j256.ormlite.misc.TransactionManager;

public class AsyncGetEvents extends Thread {
    HumbugActivity activity;
    ZulipApp app;

    Handler onRegisterHandler;
    Handler onEventsHandler;
    HTTPRequest request;

    AsyncGetEvents that = this;
    int failures = 0;
    boolean registeredOrGotEventsThisRun;

    public AsyncGetEvents(HumbugActivity humbugActivity) {
        super();
        app = humbugActivity.app;
        activity = humbugActivity;
        request = new HTTPRequest(app);
    }

    public void start() {
        registeredOrGotEventsThisRun = false;
        super.start();
    }

    public void abort() {
        // TODO: does this have race conditions? (if the thread is not in a
        // request when called)
        Log.i("asyncGetEvents", "Interrupting thread");
        request.abort();
    }

    private void backoff(Exception e) {
        if (e != null) {
            ZLog.logException(e);
        }
        failures += 1;
        long backoff = (long) (Math.exp(failures / 2.0) * 1000);
        Log.e("asyncGetEvents", "Failure " + failures + ", sleeping for "
                + backoff);
        SystemClock.sleep(backoff);
    }

    private void register() throws JSONException, IOException {
        request.setProperty("apply_markdown", "false");
        JSONObject response = new JSONObject(request.execute("POST",
                "v1/register"));

        registeredOrGotEventsThisRun = true;
        app.setEventQueueId(response.getString("queue_id"));
        app.setLastEventId(response.getInt("last_event_id"));

        processRegister(response);
    }

    public void run() {
        try {
            while (true) {
                try {
                    request.clearProperties();
                    if (app.getEventQueueId() == null) {
                        register();
                    }
                    request.setProperty("queue_id", app.getEventQueueId());
                    request.setProperty("last_event_id",
                            "" + app.getLastEventId());
                    if (registeredOrGotEventsThisRun == false) {
                        request.setProperty("dont_block", "true");
                    }
                    JSONObject response = new JSONObject(request.execute("GET",
                            "v1/events"));

                    JSONArray events = response.getJSONArray("events");
                    if (events.length() > 0) {
                        this.processEvents(events);

                        JSONObject lastEvent = events.getJSONObject(events
                                .length() - 1);
                        app.setLastEventId(lastEvent.getInt("id"));

                        failures = 0;
                    }

                    if (registeredOrGotEventsThisRun == false) {
                        registeredOrGotEventsThisRun = true;
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                activity.onReadyToDisplay(false);
                            }
                        });
                    }
                } catch (HttpResponseException e) {
                    if (e.getStatusCode() == 400) {
                        String msg = e.getMessage();
                        if (msg.contains("Bad event queue id")
                                || msg.contains("too old")) {
                            // Queue dead. Register again.
                            Log.w("asyncGetEvents", "Queue dead");
                            app.setEventQueueId(null);
                            continue;
                        }
                    }
                    backoff(e);
                } catch (SocketTimeoutException e) {
                    e.printStackTrace();
                    // Retry without backoff, since it's already been a while
                } catch (IOException e) {
                    if (request.aborting) {
                        Log.i("asyncGetEvents", "Thread aborted");
                        return;
                    } else {
                        backoff(e);
                    }
                } catch (JSONException e) {
                    backoff(e);
                }
            }
        } catch (Exception e) {
            ZLog.logException(e);
        }
    }

    protected void processRegister(final JSONObject response) {
        // In task thread
        try {
            app.setPointer(response.getInt("pointer"));
            app.setMaxMessageId(response.getInt("max_message_id"));

            Message.trim(5000, this.app);

            TransactionManager.callInTransaction(app.getDatabaseHelper()
                    .getConnectionSource(), new Callable<Void>() {
                public Void call() throws Exception {
                    // Get subscriptions
                    JSONArray subscriptions = response
                            .getJSONArray("subscriptions");
                    RuntimeExceptionDao<Stream, Object> streamDao = app
                            .getDao(Stream.class);
                    Log.i("stream", "" + subscriptions.length() + " streams");

                    for (int i = 0; i < subscriptions.length(); i++) {
                        Stream stream = Stream.getFromJSON(app,
                                subscriptions.getJSONObject(i));

                        streamDao.createOrUpdate(stream);
                    }

                    // Get people
                    JSONArray people = response.getJSONArray("realm_users");
                    RuntimeExceptionDao<Person, Object> personDao = app
                            .getDao(Person.class);
                    for (int i = 0; i < people.length(); i++) {
                        Person person = Person.getFromJSON(app,
                                people.getJSONObject(i));
                        personDao.createOrUpdate(person);
                    }
                    return null;
                }
            });

            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    that.activity.peopleAdapter.refresh();
                    that.activity.streamsAdapter.refresh();
                    activity.onReadyToDisplay(true);
                }
            });
        } catch (JSONException e) {
            ZLog.logException(e);
        } catch (SQLException e) {
            ZLog.logException(e);
        }
    }

    protected void processEvents(JSONArray events) {
        // In task thread
        try {
            ArrayList<Message> messages = new ArrayList<Message>();
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.getJSONObject(i);
                String type = event.getString("type");

                if (type.equals("message")) {
                    Message message = new Message(this.app,
                            event.getJSONObject("message"));
                    messages.add(message);
                } else if (type.equals("pointer")) {
                    // Keep our pointer synced with global pointer
                    app.setPointer(event.getInt("pointer"));
                }
            }

            if (messages.size() > 0) {
                Log.i("AsyncGetEvents", "Received " + messages.size()
                        + " messages");
                Message.createMessages(app, messages);
                processMessages(messages);
            }

        } catch (JSONException e) {
            ZLog.logException(e);
        }
    }

    protected void processMessages(final ArrayList<Message> messages) {
        // In task thread
        int lastMessageId = messages.get(messages.size() - 1).getID();
        MessageRange.updateNewMessagesRange(app, lastMessageId);

        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                activity.onNewMessages(messages.toArray(new Message[0]));
            }
        });
    }
}