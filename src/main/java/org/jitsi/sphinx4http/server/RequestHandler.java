/*
 * Sphinx4 HTTP server
 *
 * Copyright @ 2016 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jitsi.sphinx4http.server;

import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.jitsi.sphinx4http.exceptions.InvalidDirectoryException;
import org.jitsi.sphinx4http.exceptions.OperationFailedException;
import org.jitsi.sphinx4http.util.FileManager;
import org.jitsi.sphinx4http.util.SessionManager;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * This class does most of the work. It accepts incoming HTTP requests,
 * see if their content is correct, converts the given audio file to the
 * correct format, predicts the speech in said audio file, and sends the text
 * back to the requester
 *
 * @author Nik Vaessen
 */
public class RequestHandler extends AbstractHandler
{
    /**
     * The logger for this class
     */
    private static final Logger logger =
            LoggerFactory.getLogger(RequestHandler.class);

    /**
     * Keyword in the http url specifying that an audio transcription
     * is being requested
     */
    private static final String ACCEPTED_TARGET = "/recognize";

    /**
     * Keyword in the http url specifying the session id
     */
    private static final String SESSION_PARAMETER = "session-id";

    /**
     * Name of the json value holding the session id
     */
    private static final String JSON_SESSION_ID = "session-id";

    /**
     * name of the json value holding the speech-to-text result
     */
    private static final String JSON_RESULT = "result";

    /**
     * The class managing creation of directories and names for the audio files
     * which are needed for handling the speech-to-text service
     */
    private FileManager fileManager;

    /**
     * The class managing the sessions for every request
     */
    private SessionManager sessionManager;

    /**
     * The configuration of the server
     */
    private ServerConfiguration config;

    /**
     * Creates an object being able to handle incoming HTTP POST requests
     * with audio files wanting to be transcribed
     */
    public RequestHandler()
    {
        fileManager = FileManager.getInstance();
        sessionManager = new SessionManager();
        config = ServerConfiguration.getInstance();
    }

    /**
     * Handles incoming HTTP post requests. Checks for validity of the request
     * by checking if has the right url, if it's a POST request and if it
     * has an audio file as content type
     * <p>
     * It than stores the audio file to disk, converts it and transcribed the
     * audio before sending the text string back
     *
     * @param target      the target of the request - should be equal to
     *                    ACCEPTED_TARGET
     * @param baseRequest the original unwrapped request object
     * @param request     The request either as the Request object or a wrapper of
     *                    that request.
     * @param response    The response as the Response object or a wrapper of
     *                    that request.
     * @throws IOException when writing to the response object goes wrong
     */
    @SuppressWarnings("unchecked") //for JSONObject.put()
    public void handle(String target,
                       Request baseRequest,
                       HttpServletRequest request,
                       HttpServletResponse response)
            throws IOException
    {
        //log the request
        logger.info("New incoming request:\n" +
                        "target: {}\n" +
                        "method: {}\n" +
                        "Content-Type: {}\n" +
                        "Content-Length: {}\n" +
                        "Time: {}\n" +
                        "session-id: {}\n",
                target, baseRequest.getMethod(), baseRequest.getContentType(),
                baseRequest.getContentLength(), baseRequest.getHeader("Date"),
                baseRequest.getParameter("session-id"));

        //check if the address was "http://<ip>:<port>/recognize"
        if (!target.startsWith(ACCEPTED_TARGET))
        {
            sendError(HttpServletResponse.SC_BAD_REQUEST,
                    "URL needs to tail " + ACCEPTED_TARGET,
                    baseRequest, response);
            logger.info("denied request because target was " + target);
            return;
        }
        //check if request method is POST
        if (!HttpMethod.POST.asString().equals(baseRequest.getMethod()))
        {
            sendError(HttpServletResponse.SC_BAD_REQUEST,
                    "HTTP request should be POST and include an audio file",
                    baseRequest, response);
            logger.info("denied request because METHOD was not post");
            return;
        }
        //check if content type is an audio file
        if (!baseRequest.getContentType().contains("audio/"))
        {
            sendError(HttpServletResponse.SC_BAD_REQUEST,
                    "HTTP request should have content type \"audio/xxxx\"",
                    baseRequest, response);
            logger.info("denied request because content type was {}",
                baseRequest.getContentType());
            return;
        }

        //extract file
        File audioFile;
        try
        {
            audioFile = writeAudioFile(request.getInputStream(),
                    baseRequest.getContentType());
        }
        catch (IOException | InvalidDirectoryException e)
        {
            sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Failed to execute request due to " +
                    "failure in writing the audio file",
                    baseRequest, response);
            logger.warn("Unable to write an audio file");
            return;
        }

        //convert file to .wav
        File convertedFile;
        /*try
        {
            convertedFile = AudioFileManipulator.convertToWAV(audioFile,
                    fileManager.getNewFile(FileManager.CONVERTED_DIR, ".wav")
                            .getAbsolutePath());


            //delete the original audio file immediately as it's not needed
            fileManager.disposeFiles(audioFile);
        }
        catch (OperationFailedException | InvalidDirectoryException e)
        {
            sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Failed to execute request due to " +
                    "failure in converting the audio file",
                    baseRequest, response);
            fileManager.disposeFiles(audioFile);
            logger.warn("unable to convert an audio file to WAV");
            return;
        }*/
        convertedFile = audioFile;


        //check for session
        Session session;
        String sessionID;
        if ((sessionID = request.getParameter(SESSION_PARAMETER)) == null)
        {
            //make a new session
            session = sessionManager.createNewSession();
            logger.info("Created new session with id: {} ", session.getId());
        }
        else
        {
            logger.info("handling session with id: {}");
            session = sessionManager.getSession(sessionID);
            if (session == null)
            {
                sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                        "invalid session id", baseRequest, response);
                fileManager.disposeFiles(audioFile);
                logger.info("request had invalid id: {}", sessionID);
                return;
            }
        }

        //finish the rest of the request
        if(config.isChunkedResponse())
        {
            transcribeRequestChunked(baseRequest, response,session,
                    convertedFile);
        }
        else
        {
            transcribeRequest(baseRequest, response, session, convertedFile);
        }
    }

    /**
     * When a request has been verified and accepted, this method will manage
     * the actual transcription work. This method will transcribe the whole file
     * and sent the result when the transcription is completely done
     * @param baseRequest the baseRequest
     * @param response the response of the server
     * @param session the session belonging to the request
     * @param audioFile the audio file which is going to get transcribed
     * @throws IOException when writing the response goes wrong
     */
    @SuppressWarnings("unchecked") //for JSONObject.put()
    private void transcribeRequest(Request baseRequest,
                                   HttpServletResponse response,
                                   Session session,
                                   File audioFile)
            throws IOException
    {
        //get the speech-to-text
        JSONArray speechToTextResult;
        try
        {
            logger.info("Started audio transcription for id: {}",
                    session.getId());
                speechToTextResult = session.transcribe(audioFile);
        }
        catch (IOException e)
        {
            sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Failed to execute request due to" +
                            "an error in transcribing the audio file",
                    baseRequest, response);
            return;
        }

        //create the returning json result with the session id
        JSONObject result = new JSONObject();
        result.put(JSON_SESSION_ID, session.getId());
        result.put(JSON_RESULT, speechToTextResult);

        //return the json result
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        response.getWriter().write(result.toJSONString());
        baseRequest.setHandled(true);

        //log result
        logger.info("Successfully handled request with id: {}",
                session.getId());
        logger.debug("Result of request with id {}:\n{}", session.getId(),
                result.toJSONString());
    }

    /**
     * When a request has been verified and accepted, this method will manage
     * the actual transcription work. This method will transcribe the whole file
     * and sent the result when the transcription is completely done
     * @param baseRequest the baseRequest
     * @param response the response of the server
     * @param session the session belonging to the request
     * @param audioFile the audio file which is going to get transcribed
     * @throws IOException when writing the response goes wrong
     */
    @SuppressWarnings("unchecked") //for JSONObject.put()
    private void transcribeRequestChunked(Request baseRequest,
                                          HttpServletResponse response,
                                          Session session,
                                          File audioFile)
            throws IOException
    {
        //start by sending 200 OK and the meta data JSON object
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");

        JSONObject object = new JSONObject();
        object.put(JSON_SESSION_ID, session.getId());

        //and send the initial object
        response.getWriter().write("{\"objects\":[" + object.toJSONString());
        response.getWriter().flush();

        //start the transcription, which will send
        //JSON objects constantly
        JSONArray result;
        try
        {
            logger.info("Started audio transcription for id: {}",
                    session.getId());
            result = session.chunkedTranscribe(audioFile,
                    response.getWriter());
        }
        catch (IOException e)
        {
            logger.warn("chunked transcription with id {} failed due to " +
                    "IO error", e);
            sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Failed to execute request due to" +
                            "an error in transcribing the audio file",
                    baseRequest, response);
            return;
        }

        //the request is handled
        response.getWriter().write("]}");
        response.getWriter().flush();
        baseRequest.setHandled(true);

        //log result
        logger.info("Successfully handled request with id: {}",
                session.getId());
        logger.debug("Result of chunked request with id {}:\n{}",
                session.getId(), result.toJSONString());
    }

    /**
     * Method to finish the request with an error
     * @param status the http error status
     * @param message the message of the error
     * @param baseRequest the original request to reply to
     * @param response the HttpServletResponse object to use as an reply
     */
    private void sendError(int status, String message, Request baseRequest,
                           HttpServletResponse response)
    {
        response.setStatus(status);
        try
        {
            response.setContentType("text/plain");
            response.getWriter().println(message);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
        baseRequest.setHandled(true);
    }

    /**
     * Writes the audio file given in the HTTP request to file
     *
     * @param inputStream the InputStream of the audio file object
     * @param contentType the content type of the HTTP request. Needed to give
     *                    the written file the correct file extension
     * @return The file object if the audio file in hte HTTP request,
     * written to disk
     * @throws IOException when reading from the InputStream goes wrong
     */
    private File writeAudioFile(InputStream inputStream, String contentType)
            throws IOException, InvalidDirectoryException
    {
        String content = contentType.split("/")[0];
        File audioFile = fileManager.getNewFile(FileManager.INCOMING_DIR,
                content);
        try (FileOutputStream outputStream = new FileOutputStream(audioFile))
        {
            byte[] buffer = new byte[2048];
            while (inputStream.read(buffer) != -1)
            {
                outputStream.write(buffer);
            }
            inputStream.close();
            return audioFile;
        }
        catch (IOException e)
        {
            e.printStackTrace();
            throw e;
        }
    }

}
