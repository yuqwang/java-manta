/**
 * Copyright (c) 2014, Joyent, Inc. All rights reserved.
 */
package com.joyent.manta.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.EmptyContent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpContent;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.InputStreamContent;
import com.google.api.client.util.ObjectParser;
import com.joyent.http.signature.HttpSignerUtils;
import com.joyent.http.signature.google.httpclient.HttpSigner;
import com.joyent.manta.config.ConfigContext;
import com.joyent.manta.config.DefaultsConfigContext;
import com.joyent.manta.exception.MantaClientException;
import com.joyent.manta.exception.MantaClientHttpResponseException;
import com.joyent.manta.exception.MantaErrorCode;
import com.joyent.manta.exception.MantaObjectException;
import org.apache.http.entity.ContentType;
import org.apache.http.protocol.HTTP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.KeyPair;
import java.time.Instant;
import java.time.temporal.TemporalAmount;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Stream;

import static com.joyent.manta.client.MantaUtils.formatPath;

/**
 * Manta client object that allows for doing CRUD operations against the Manta HTTP
 * API. Using this client you can retrieve implementations of {@link MantaObject}.
 *
 * @author Yunong Xiao
 */
public class MantaClient implements AutoCloseable {

    /**
     * The static logger instance.
     */
    private static final Logger LOG = LoggerFactory.getLogger(MantaClient.class);

    /**
     * The provider for http requests setup, metadata and request initialization.
     */
    private final HttpRequestFactoryProvider httpRequestFactoryProvider;

    /**
     * The standard http status code representing that the resource could not be found.
     */
    private static final int HTTP_STATUSCODE_404_NOT_FOUND = 404;


    /**
     * The standard http status code representing that the request was accepted.
     */
    private static final int HTTP_STATUSCODE_202_ACCEPTED = 202;

    /**
     * The content-type used to represent Manta link resources.
     */
    private static final String LINK_CONTENT_TYPE = "application/json; type=link";

    /**
     * The content-type used to represent Manta directory resources in http requests.
     */
    private static final String DIRECTORY_REQUEST_CONTENT_TYPE = "application/json; type=directory";

    /**
     * HTTP metadata headers that violate the Manta API contract.
     */
    private static final String[] ILLEGAL_METADATA_HEADERS = new String[]{
            HTTP.CONTENT_LEN, "Content-MD5", "Durability-Level"
    };


    /**
     * {@link ObjectParser} implementation used for parsing JSON HTTP responses.
     */
    private static final MantaObjectParser OBJECT_PARSER =
            new MantaObjectParser();


    /**
     * A string representation of the manta service endpoint URL.
     */
    private final String url;


    /**
     * The instance of the http signer used to sign the http requests.
     */
    private final HttpSigner httpSigner;


    /**
     * The home directory of the account.
     */
    private final String home;


    /**
     * Creates a new instance of a Manta client.
     *
     * @param config The configuration context that provides all of the configuration values.
     * @throws IOException If unable to instantiate the client.
     */
    public MantaClient(final ConfigContext config) throws IOException {
        this(config.getMantaURL(), config.getMantaUser(), config.getMantaKeyPath(),
                config.getMantaKeyId());
    }


    /**
     * Creates a new instance of a Manta client.
     *
     * @param url         The url of the Manta endpoint.
     * @param account     The account used to login.
     * @param keyPath     The path to the user rsa private key on disk.
     * @param fingerPrint The fingerprint of the user rsa private key.
     * @throws IOException If unable to instantiate the client.
     */
    public MantaClient(final String url, final String account, final String keyPath,
                       final String fingerPrint) throws IOException {
        this(url, account, keyPath, fingerPrint, DefaultsConfigContext.DEFAULT_HTTP_TIMEOUT);
    }


    /**
     * Creates a new instance of a Manta client.
     *
     * @param url               The url of the Manta endpoint.
     * @param account     The account used to login.
     * @param privateKeyContent The user's rsa private key as a string.
     * @param fingerPrint       The fingerprint of the user rsa private key.
     * @param password          The private key password (optional).
     * @throws IOException If unable to instantiate the client.
     */
    public MantaClient(final String url,
                       final String account,
                       final String privateKeyContent,
                       final String fingerPrint,
                       final char[] password) throws IOException {
        this(url, account, privateKeyContent, fingerPrint, password,
                DefaultsConfigContext.DEFAULT_HTTP_TIMEOUT);
    }


    /**
     * Instantiates a new Manta client instance.
     *
     * @param url         The url of the Manta endpoint.
     * @param account     The account used to login.
     * @param keyPath     The path to the user rsa private key on disk.
     * @param fingerprint The fingerprint of the user rsa private key.
     * @param httpTimeout The HTTP timeout in milliseconds.
     * @throws IOException If unable to instantiate the client.
     */
    public MantaClient(final String url,
                       final String account,
                       final String keyPath,
                       final String fingerprint,
                       final int httpTimeout) throws IOException {
        if (account == null) {
            throw new IllegalArgumentException("Manta account name must be specified");
        }
        if (url == null) {
            throw new IllegalArgumentException("Manta URL must be specified");
        }
        if (keyPath == null) {
            throw new IllegalArgumentException("Manta key path must be specified");
        }
        if (fingerprint == null) {
            throw new IllegalArgumentException("Manta key id must be specified");
        }
        if (httpTimeout < 0) {
            throw new IllegalArgumentException("Manta timeout must be 0 or greater");
        }

        this.url = url;
        KeyPair keyPair = HttpSignerUtils.getKeyPair(new File(keyPath).toPath());
        this.httpSigner = new HttpSigner(keyPair, account, fingerprint);
        this.httpRequestFactoryProvider = new HttpRequestFactoryProvider(httpSigner,
                httpTimeout);
        this.home = ConfigContext.deriveHomeDirectoryFromUser(account);
    }


    /**
     * Instantiates a new Manta client instance.
     *
     * @param url               The url of the Manta endpoint.
     * @param account     The account used to login.
     * @param privateKeyContent The private key as a string.
     * @param fingerprint       The name of the key.
     * @param password          The private key password (optional).
     * @param httpTimeout       The HTTP timeout in milliseconds.
     * @throws IOException If unable to instantiate the client.
     */
    public MantaClient(final String url,
                       final String account,
                       final String privateKeyContent,
                       final String fingerprint,
                       final char[] password,
                       final int httpTimeout) throws IOException {
        if (account == null) {
            throw new IllegalArgumentException("Manta username must be specified");
        }
        if (url == null) {
            throw new IllegalArgumentException("Manta URL must be specified");
        }
        if (fingerprint == null) {
            throw new IllegalArgumentException("Manta fingerprint must be specified");
        }
        if (password == null) {
            throw new IllegalArgumentException("Manta key password must be specified");
        }
        if (httpTimeout < 0) {
            throw new IllegalArgumentException("Manta timeout must be 0 or greater");
        }

        this.url = url;
        KeyPair keyPair = HttpSignerUtils.getKeyPair(privateKeyContent, password);
        this.httpSigner = new HttpSigner(keyPair, account, fingerprint);
        this.home = ConfigContext.deriveHomeDirectoryFromUser(account);
        this.httpRequestFactoryProvider = new HttpRequestFactoryProvider(httpSigner,
                httpTimeout);
    }


    /**
     * Deletes an object from Manta.
     *
     * @param path The fully qualified path of the Manta object.
     * @throws IOException                                     If an IO exception has occurred.
     * @throws com.joyent.manta.exception.MantaCryptoException If there's an exception while signing the request.
     * @throws MantaClientHttpResponseException                If an HTTP status code {@literal > 300} is returned.
     */
    public void delete(final String path) throws IOException {
        LOG.debug("DELETE {}", path);

        final GenericUrl genericUrl = new GenericUrl(this.url + formatPath(path));
        final HttpRequestFactory httpRequestFactory = httpRequestFactoryProvider.getRequestFactory();
        final HttpRequest request = httpRequestFactory.buildDeleteRequest(genericUrl);

        executeAndCloseRequest(request, "DELETE {} response [{}] {} ", path);
    }


    /**
     * Recursively deletes an object in Manta.
     *
     * @param path The fully qualified path of the Manta object.
     * @throws IOException                                     If an IO exception has occurred.
     * @throws com.joyent.manta.exception.MantaCryptoException If there's an exception while signing the request.
     * @throws MantaClientHttpResponseException                If a http status code {@literal > 300} is returned.
     */
    public void deleteRecursive(final String path) throws IOException {
        LOG.debug("DELETE {} [recursive]", path);
        Collection<MantaObject> objs;
        try {
            objs = this.listObjects(path);
        } catch (final MantaObjectException e) {
            this.delete(path);
            LOG.debug("Finished deleting path {}", path);
            return;
        }
        for (final MantaObject mantaObject : objs) {
            if (mantaObject.isDirectory()) {
                this.deleteRecursive(mantaObject.getPath());
            } else {
                this.delete(mantaObject.getPath());
            }
        }

        try {
            this.delete(path);
        } catch (MantaClientHttpResponseException e) {
            if (e.getStatusCode() == HTTP_STATUSCODE_404_NOT_FOUND && LOG.isDebugEnabled()) {
                LOG.debug("Couldn't delete object because it doesn't exist", e);
            } else {
                throw e;
            }
        }

        LOG.debug("Finished deleting path {}", path);
    }


    /**
     * Get the metadata for a Manta object. The difference with this method vs head() is
     * that the request being made against the Manta API is done via a GET.
     *
     * @param path The fully qualified path of the object. i.e. /user/stor/foo/bar/baz
     * @return The {@link MantaObjectResponse}.
     * @throws IOException                                     If an IO exception has occurred.
     * @throws com.joyent.manta.exception.MantaCryptoException If there's an exception while signing the request.
     * @throws MantaClientHttpResponseException                If a http status code {@literal > 300} is returned.
     */
    public MantaObjectResponse get(final String path) throws IOException {
        final HttpResponse response = httpGet(path);

        try {
            final MantaHttpHeaders headers = new MantaHttpHeaders(response.getHeaders());
            return new MantaObjectResponse(path, headers);
        } finally {
            response.disconnect();
        }
    }

    /**
     * Get a Manta object's data as an {@link InputStream}. This method allows you to
     * stream data from the Manta storage service in a memory efficient manner to your
     * application.
     *
     * @param path The fully qualified path of the object. i.e. /user/stor/foo/bar/baz
     * @return {@link InputStream} that extends {@link MantaObjectResponse}.
     * @throws IOException when there is a problem getting the object over the network
     */
    public MantaObjectInputStream getAsInputStream(final String path) throws IOException {
        final HttpResponse response = httpGet(path);
        final MantaHttpHeaders headers = new MantaHttpHeaders(response.getHeaders());
        final MantaObjectResponse metadata = new MantaObjectResponse(path, headers);

        if (metadata.isDirectory()) {
            String msg = String.format("Directories do not have data, so "
                            + "data streams from them doesn't work. Path requested: %s",
                    path);
            throw new MantaClientException(msg);
        }

        return new MantaObjectInputStream(metadata, response);
    }


    /**
     * Get a Manta object's data as a {@link String} using the JVM's default encoding.
     * This method is not memory efficient, by loading the data into a String you are
     * loading all of the Object's data into memory.
     *
     * @param path The fully qualified path of the object. i.e. /user/stor/foo/bar/baz
     * @return String containing the entire Manta object
     * @throws IOException when there is a problem getting the object over the network
     */
    public String getAsString(final String path) throws IOException {
        try (InputStream is = getAsInputStream(path)) {
            return MantaUtils.inputStreamToString(is);
        }
    }


    /**
     * Get a Manta object's data as a {@link String} using the specified encoding.
     * This method is not memory efficient, by loading the data into a String you are
     * loading all of the Object's data into memory.
     *
     * @param path        The fully qualified path of the object. i.e. /user/stor/foo/bar/baz
     * @param charsetName The encoding type used to convert bytes from the
     *                    stream into characters to be scanned
     * @return String containing the entire Manta object
     * @throws IOException when there is a problem getting the object over the network
     */
    public String getAsString(final String path, final String charsetName) throws IOException {
        try (InputStream is = getAsInputStream(path)) {
            return MantaUtils.inputStreamToString(is, charsetName);
        }
    }


    /**
     * Copies Manta object's data to a temporary file on the file system and return
     * a reference to the file using a NIO {@link Path}. This method is memory
     * efficient because it uses streams to do the copy.
     *
     * @param path The fully qualified path of the object. i.e. /user/stor/foo/bar/baz
     * @return reference to the temporary file as a {@link Path} object
     * @throws IOException when there is a problem getting the object over the network
     */
    public Path getToTempPath(final String path) throws IOException {
        try (InputStream is = getAsInputStream(path)) {
            final Path temp = Files.createTempFile("manta-object", "tmp");

            Files.copy(is, temp, StandardCopyOption.REPLACE_EXISTING);

            return temp;
        }
    }


    /**
     * Copies Manta object's data to a temporary file on the file system and return
     * a reference to the file using a {@link File}. This method is memory
     * efficient because it uses streams to do the copy.
     *
     * @param path The fully qualified path of the object. i.e. /user/stor/foo/bar/baz
     * @return reference to the temporary file as a {@link File} object
     * @throws IOException when there is a problem getting the object over the network
     */
    public File getToTempFile(final String path) throws IOException {
        return getToTempPath(path).toFile();
    }


    /**
     * Get a Manta object's data as an NIO {@link SeekableByteChannel}. This method
     * allows you to stream data from the Manta storage service in a memory efficient
     * manner to your application. Unlike an {@link InputStream}, this will allow you
     * to stream data by moving between arbitrary position in the Manta object data.
     *
     * @param path The fully qualified path of the object. i.e. /user/stor/foo/bar/baz
     * @return seekable stream of object data
     * @throws IOException when there is a problem getting the object over the network
     */
    public SeekableByteChannel getSeekableByteChannel(final String path) throws IOException {
        Objects.requireNonNull(path, "Path must not be null");

        final GenericUrl genericUrl = new GenericUrl(this.url + formatPath(path));

        return new MantaSeekableByteChannel(genericUrl,
                httpRequestFactoryProvider.getRequestFactory());
    }


    /**
     * Get a Manta object's data as an NIO {@link SeekableByteChannel}. This method
     * allows you to stream data from the Manta storage service in a memory efficient
     * manner to your application. Unlike an {@link InputStream}, this will allow you
     * to stream data by moving between arbitrary position in the Manta object data.
     *
     * @param path     The fully qualified path of the object. i.e. /user/stor/foo/bar/baz
     * @param position The starting position (in number of bytes) to read from
     * @return seekable stream of object data
     * @throws IOException when there is a problem getting the object over the network
     */
    public SeekableByteChannel getSeekableByteChannel(final String path,
                                                      final long position) throws IOException {
        Objects.requireNonNull(path, "Path must not be null");

        final GenericUrl genericUrl = new GenericUrl(this.url + formatPath(path));

        return new MantaSeekableByteChannel(genericUrl, position,
                httpRequestFactoryProvider.getRequestFactory());
    }

    /**
     * Executes a HTTP GET against the remote Manta API.
     *
     * @param path The fully qualified path of the object. i.e. /user/stor/foo/bar/baz
     * @return Google HTTP Client response object
     * @throws IOException when there is a problem getting the object over the network
     */
    protected HttpResponse httpGet(final String path) throws IOException {
        return httpGet(path, null);
    }


    /**
     * Executes a HTTP GET against the remote Manta API.
     *
     * @param path The fully qualified path of the object. i.e. /user/stor/foo/bar/baz
     * @param parser Parser used for parsing response into a POJO
     * @return Google HTTP Client response object
     * @throws IOException when there is a problem getting the object over the network
     */
    protected HttpResponse httpGet(final String path,
                                   final ObjectParser parser) throws IOException {
        Objects.requireNonNull(path, "Path must not be null");

        final GenericUrl genericUrl = new GenericUrl(this.url + formatPath(path));
        return httpGet(genericUrl, parser);
    }


    /**
     * Executes a HTTP GET against the remote Manta API.
     *
     * @param genericUrl The URL to the object on Manta
     * @param parser Parser used for parsing response into a POJO
     * @return Google HTTP Client response object
     * @throws IOException when there is a problem getting the object over the network
     */
    protected HttpResponse httpGet(final GenericUrl genericUrl,
                                   final ObjectParser parser) throws IOException {
        Objects.requireNonNull(genericUrl, "URL must be present");

        LOG.debug("GET    {}", genericUrl.getRawPath());

        final HttpRequestFactory httpRequestFactory = httpRequestFactoryProvider.getRequestFactory();
        final HttpRequest request = httpRequestFactory.buildGetRequest(genericUrl);

        if (parser != null) {
            request.setParser(parser);
        }

        final HttpResponse response;

        try {
            response = request.execute();
            LOG.debug("GET    {} response [{}] {} ",
                    genericUrl.getRawPath(),
                    response.getStatusCode(),
                    response.getStatusMessage());
        } catch (final HttpResponseException e) {
            throw new MantaClientHttpResponseException(e);
        }

        return response;
    }


    /**
     * <p>Generates a URL that allows for the download of the resource specified
     * in the path without any additional authentication.</p>
     *
     * <p>This could be useful for when you want to generate links that expire
     * after N seconds to give to external users for temporary download
     * access.</p>
     *
     * @param path The fully qualified path of the object. i.e. /user/stor/foo/bar/baz
     * @param method the String GET or the string HEAD. This is the HTTP verb
     *               that will be used when requesting this resource
     * @param expiresIn time from now on when resource expires
     * @return a signed URL that allows for downloading a resource
     * @throws IOException thrown if there is a problem generating the URL
     */
    public URI getAsSignedURI(final String path, final String method,
                              final TemporalAmount expiresIn)
            throws IOException {
        Objects.requireNonNull(expiresIn, "Duration must be present");
        final Instant expires = Instant.now().plus(expiresIn);
        return getAsSignedURI(path, method, expires);
    }


    /**
     * <p>Generates a URL that allows for the download of the resource specified
     * in the path without any additional authentication.</p>
     *
     * <p>This could be useful for when you want to generate links that expire
     * after N seconds to give to external users for temporary download
     * access.</p>
     *
     * @param path The fully qualified path of the object. i.e. /user/stor/foo/bar/baz
     * @param method the String GET or the string HEAD. This is the HTTP verb
     *               that will be used when requesting this resource
     * @param expires time when resource expires and become unavailable
     * @return a signed URL that allows for downloading a resource
     * @throws IOException thrown if there is a problem generating the URL
     */
    public URI getAsSignedURI(final String path, final String method,
                              final Instant expires)
            throws IOException {
        Objects.requireNonNull(path, "Path must be present");
        Objects.requireNonNull(expires, "Expires must be present");

        final String fullPath = String.format("%s%s", this.url, formatPath(path));
        final URI request = URI.create(fullPath);

        return httpSigner.signURI(request, method, expires.getEpochSecond());
    }


    /**
     * Get the metadata associated with a Manta object.
     *
     * @param path The fully qualified path of the object. i.e. /user/stor/foo/bar/baz
     * @return The {@link MantaObjectResponse}.
     * @throws IOException                                     If an IO exception has occurred.
     * @throws com.joyent.manta.exception.MantaCryptoException If there's an exception while signing the request.
     * @throws MantaClientHttpResponseException                If a http status code {@literal > 300} is returned.
     */
    public MantaObjectResponse head(final String path) throws IOException {
        final HttpResponse response = httpHead(path);
        final MantaHttpHeaders headers = new MantaHttpHeaders(response.getHeaders());
        return new MantaObjectResponse(path, headers);
    }


    /**
     * Executes a HTTP HEAD against the remote Manta API.
     *
     * @param path The fully qualified path of the object. i.e. /user/stor/foo/bar/baz
     * @return Google HTTP Client response object
     * @throws IOException when there is a problem getting the object over the network
     */
    protected HttpResponse httpHead(final String path) throws IOException {
        Objects.requireNonNull(path, "Path must not be null");

        LOG.debug("HEAD   {}", path);

        final GenericUrl genericUrl = new GenericUrl(this.url + formatPath(path));
        final HttpRequestFactory httpRequestFactory = httpRequestFactoryProvider.getRequestFactory();
        final HttpRequest request = httpRequestFactory.buildHeadRequest(genericUrl);

        final HttpResponse response;

        try {
            response = request.execute();
            LOG.debug("HEAD   {} response [{}] {} ", path, response.getStatusCode(),
                    response.getStatusMessage());
            return response;
        } catch (final HttpResponseException e) {
            throw new MantaClientHttpResponseException(e);
        }
    }


    /**
     * Return the contents of a directory in Manta.
     *
     * @param path The fully qualified path of the directory.
     * @return A {@link Collection} of {@link MantaObjectResponse} listing the contents of the directory.
     * @throws IOException                                     If an IO exception has occurred.
     * @throws com.joyent.manta.exception.MantaCryptoException If there's an exception while signing the request.
     * @throws MantaObjectException                            If the path isn't a directory
     * @throws MantaClientHttpResponseException                If a http status code {@literal > 300} is returned.
     */
    public Collection<MantaObject> listObjects(final String path) throws IOException {
        LOG.debug("GET    {} [list]", path);
        final GenericUrl genericUrl = new GenericUrl(this.url + formatPath(path));
        final HttpRequestFactory httpRequestFactory = httpRequestFactoryProvider.getRequestFactory();
        final HttpRequest request = httpRequestFactory.buildGetRequest(genericUrl);

        HttpResponse response = null;
        try {
            response = request.execute();
            LOG.debug("GET    {} response [{}] {} ", path, response.getStatusCode(),
                    response.getStatusMessage());

            if (!response.getContentType().equals(MantaObject.DIRECTORY_HEADER)) {
                throw new MantaObjectException("Object is not a directory");
            }

            final BufferedReader br = new BufferedReader(new InputStreamReader(response.getContent()));
            final ObjectParser parser = request.getParser();
            return buildObjects(path, br, parser);
        } catch (final HttpResponseException e) {
            throw new MantaClientHttpResponseException(e);
        } finally {
            if (response != null) {
                response.disconnect();
            }
        }
    }


    /**
     * Convenience method that issues a HTTP HEAD request to see if a given
     * object exists at the specified path.
     *
     * @param path The fully qualified path of the object. i.e. /user/stor/foo/bar/baz
     * @return true if Manta returns a 2xx status code and false if Manta returns
     *         any error status code or if there was a connection problem (IOException)
     */
    public boolean existsAndIsAccessible(final String path) {
        try {
            httpHead(path);
        } catch (IOException e) {
            return false;
        }

        return true;
    }


    /**
     * Creates a list of {@link MantaObjectResponse}s based on the HTTP response from Manta.
     *
     * @param path    The fully qualified path of the directory.
     * @param content The content of the response as a Reader.
     * @param parser  Deserializer implementation that takes the raw content
     *                and turns it into a {@link MantaObjectResponse}
     * @return List of {@link MantaObjectResponse}s for a given directory
     * @throws IOException If an IO exception has occurred.
     */
    protected static List<MantaObject> buildObjects(final String path,
                                                    final BufferedReader content,
                                                    final ObjectParser parser) throws IOException {
        final ArrayList<MantaObject> objs = new ArrayList<>();
        String line;
        StringBuilder myPath = new StringBuilder(path);
        while ((line = content.readLine()) != null) {
            final MantaObjectResponse obj = parser.parseAndClose(new StringReader(line), MantaObjectResponse.class);
            // need to prefix the obj name with the fully qualified path, since Manta only returns
            // the explicit name of the object.
            if (!MantaUtils.endsWith(myPath, '/')) {
                myPath.append('/');
            }

            obj.setPath(myPath + obj.getPath());
            objs.add(obj);
        }
        return objs;
    }


    /**
     * Puts an object into Manta.
     *
     * @param path    The path to the Manta object.
     * @param source  {@link InputStream} to copy object data from
     * @param headers optional HTTP headers to include when copying the object
     * @return Manta response object
     * @throws IOException                                     If an IO exception has occurred.
     * @throws com.joyent.manta.exception.MantaCryptoException If there's an exception while signing the request.
     * @throws MantaClientHttpResponseException                If a http status code {@literal > 300} is returned.
     */
    public MantaObjectResponse put(final String path,
                                   final InputStream source,
                                   final MantaHttpHeaders headers) throws IOException {
        Objects.requireNonNull(path, "Path must not be null");

        final String contentType = findOrDefaultContentType(headers,
                ContentType.APPLICATION_OCTET_STREAM.toString());

        final HttpContent content;

        if (source == null) {
            content = new EmptyContent();
        } else {
            content = new InputStreamContent(contentType, source);
        }

        return httpPut(path, headers, content, null);
    }

    /**
     * Puts an object into Manta.
     *
     * @param path     The path to the Manta object.
     * @param source   {@link InputStream} to copy object data from
     * @param metadata optional user-supplied metadata for object
     * @return Manta response object
     * @throws IOException                                     If an IO exception has occurred.
     * @throws com.joyent.manta.exception.MantaCryptoException If there's an exception while signing the request.
     * @throws MantaClientHttpResponseException                If a http status code {@literal > 300} is returned.
     */
    public MantaObjectResponse put(final String path,
                                   final InputStream source,
                                   final MantaMetadata metadata) throws IOException {
        Objects.requireNonNull(path, "Path must not be null");

        final String contentType = ContentType.APPLICATION_OCTET_STREAM.toString();

        final HttpContent content;

        if (source == null) {
            content = new EmptyContent();
        } else {
            content = new InputStreamContent(contentType, source);
        }

        return httpPut(path, null, content, metadata);
    }


    /**
     * Puts an object into Manta.
     *
     * @param path     The path to the Manta object.
     * @param source   {@link InputStream} to copy object data from
     * @param headers  optional HTTP headers to include when copying the object
     * @param metadata optional user-supplied metadata for object
     * @return Manta response object
     * @throws IOException                                     If an IO exception has occurred.
     * @throws com.joyent.manta.exception.MantaCryptoException If there's an exception while signing the request.
     * @throws MantaClientHttpResponseException                If a http status code {@literal > 300} is returned.
     */
    public MantaObjectResponse put(final String path,
                                   final InputStream source,
                                   final MantaHttpHeaders headers,
                                   final MantaMetadata metadata) throws IOException {
        Objects.requireNonNull(path, "Path must not be null");

        final String contentType = findOrDefaultContentType(headers,
                ContentType.APPLICATION_OCTET_STREAM.toString());

        final HttpContent content;

        if (source == null) {
            content = new EmptyContent();
        } else {
            content = new InputStreamContent(contentType, source);
        }

        return httpPut(path, headers, content, metadata);
    }

    /**
     * Executes an HTTP PUT against the remote Manta API.
     *
     * @param path     The fully qualified path of the object. i.e. /user/stor/foo/bar/baz
     * @param headers  optional HTTP headers to include when copying the object
     * @param content  Google HTTP Client content object
     * @param metadata optional user-supplied metadata for object
     * @return Manta response object
     * @throws IOException when there is a problem sending the object over the network
     */
    protected MantaObjectResponse httpPut(final String path,
                                          final MantaHttpHeaders headers,
                                          final HttpContent content,
                                          final MantaMetadata metadata)
            throws IOException {
        final GenericUrl genericUrl = new GenericUrl(this.url + formatPath(path));
        return httpPut(genericUrl, headers, content, metadata);
    }


    /**
     * Executes an HTTP PUT against the remote Manta API.
     *
     * @param genericUrl Full URL to the object on the Manta API
     * @param headers    optional HTTP headers to include when copying the object
     * @param content    Google HTTP Client content object
     * @param metadata   optional user-supplied metadata for object
     * @return Manta response object
     * @throws IOException when there is a problem sending the object over the network
     */
    protected MantaObjectResponse httpPut(final GenericUrl genericUrl,
                                          final MantaHttpHeaders headers,
                                          final HttpContent content,
                                          final MantaMetadata metadata)
            throws IOException {
        final String path = genericUrl.getRawPath();
        LOG.debug("PUT    {}", path);

        final MantaHttpHeaders httpHeaders;

        if (headers == null) {
            httpHeaders = new MantaHttpHeaders();
        } else {
            httpHeaders = headers;
        }

        if (metadata != null) {
            httpHeaders.putAll(metadata);
        }

        final HttpRequestFactory httpRequestFactory = httpRequestFactoryProvider.getRequestFactory();
        final HttpRequest request = httpRequestFactory.buildPutRequest(genericUrl, content);

        request.setHeaders(httpHeaders.asGoogleClientHttpHeaders());

        HttpResponse response = null;
        try {
            response = request.execute();
            LOG.debug("PUT    {} response [{}] {} ", path, response.getStatusCode(),
                    response.getStatusMessage());
            final MantaHttpHeaders responseHeaders = new MantaHttpHeaders(response.getHeaders());
            // We add back in the metadata made in the request so that it is easily available
            responseHeaders.putAll(httpHeaders.metadata());

            return new MantaObjectResponse(path, responseHeaders, metadata);
        } catch (final HttpResponseException e) {
            throw new MantaClientHttpResponseException(e);
        } finally {
            if (response != null) {
                response.disconnect();
            }
        }
    }


    /**
     * Copies the supplied {@link InputStream} to a remote Manta object at the
     * specified path.
     *
     * @param path   The fully qualified path of the object. i.e. /user/stor/foo/bar/baz
     * @param source the {@link InputStream} to copy data from
     * @return Manta response object
     * @throws IOException when there is a problem sending the object over the network
     */
    public MantaObjectResponse put(final String path, final InputStream source) throws IOException {
        return put(path, source, null, null);
    }


    /**
     * Copies the supplied {@link String} to a remote Manta object at the specified
     * path using the default JVM character encoding as a binary representation.
     *
     * @param path    The fully qualified path of the object. i.e. /user/stor/foo/bar/baz
     * @param string  string to copy
     * @param headers optional HTTP headers to include when copying the object
     * @return Manta response object
     * @throws IOException when there is a problem sending the object over the network
     */
    public MantaObjectResponse put(final String path,
                                   final String string,
                                   final MantaHttpHeaders headers) throws IOException {
        try (InputStream is = new ByteArrayInputStream(string.getBytes())) {
            return put(path, is, headers);
        }
    }


    /**
     * Copies the supplied {@link String} to a remote Manta object at the specified
     * path using the default JVM character encoding as a binary representation.
     *
     * @param path     The fully qualified path of the object. i.e. /user/stor/foo/bar/baz
     * @param string   string to copy
     * @param metadata optional user-supplied metadata for object
     * @return Manta response object
     * @throws IOException when there is a problem sending the object over the network
     */
    public MantaObjectResponse put(final String path,
                                   final String string,
                                   final MantaMetadata metadata) throws IOException {
        try (InputStream is = new ByteArrayInputStream(string.getBytes())) {
            return put(path, is, null, metadata);
        }
    }


    /**
     * Copies the supplied {@link String} to a remote Manta object at the specified
     * path using the default JVM character encoding as a binary representation.
     *
     * @param path     The fully qualified path of the object. i.e. /user/stor/foo/bar/baz
     * @param string   string to copy
     * @param headers  optional HTTP headers to include when copying the object
     * @param metadata optional user-supplied metadata for object
     * @return Manta response object
     * @throws IOException when there is a problem sending the object over the network
     */
    public MantaObjectResponse put(final String path,
                                   final String string,
                                   final MantaHttpHeaders headers,
                                   final MantaMetadata metadata) throws IOException {
        try (InputStream is = new ByteArrayInputStream(string.getBytes())) {
            return put(path, is, headers, metadata);
        }
    }

    /**
     * Copies the supplied {@link String} to a remote Manta object at the specified
     * path using the default JVM character encoding as a binary representation.
     *
     * @param path   The fully qualified path of the object. i.e. /user/stor/foo/bar/baz
     * @param string string to copy
     * @return Manta response object
     * @throws IOException when there is a problem sending the object over the network
     */
    public MantaObjectResponse put(final String path,
                                   final String string) throws IOException {
        return put(path, string, null, null);
    }

    /**
     * Appends the specified metadata to an existing Manta object.
     *
     * @param path     The fully qualified path of the object. i.e. /user/stor/foo/bar/baz
     * @param metadata user-supplied metadata for object
     * @return Manta response object
     * @throws IOException when there is a problem sending the metadata over the network
     */
    public MantaObjectResponse putMetadata(final String path, final MantaMetadata metadata)
            throws IOException {
        final MantaHttpHeaders headers = new MantaHttpHeaders(metadata);
        return putMetadata(path, headers, metadata);
    }


    /**
     * Appends metadata derived from HTTP headers to an existing Manta object.
     *
     * @param path     The fully qualified path of the object. i.e. /user/stor/foo/bar/baz
     * @param headers  HTTP headers to include when copying the object
     * @return Manta response object
     * @throws IOException when there is a problem sending the metadata over the network
     */
    public MantaObjectResponse putMetadata(final String path, final MantaHttpHeaders headers)
            throws IOException {
        Objects.requireNonNull(headers, "Headers must be present");

        final MantaMetadata metadata = new MantaMetadata(headers.metadataAsStrings());
        return putMetadata(path, headers, metadata);
    }


    /**
     * Appends the specified metadata to an existing Manta object using the
     * specified HTTP headers.
     *
     * @param path     The fully qualified path of the object. i.e. /user/stor/foo/bar/baz
     * @param headers  HTTP headers to include when copying the object
     * @param metadata user-supplied metadata for object
     * @return Manta response object
     * @throws IOException when there is a problem sending the metadata over the network
     */
    public MantaObjectResponse putMetadata(final String path,
                                           final MantaHttpHeaders headers,
                                           final MantaMetadata metadata)
            throws IOException {
        Objects.requireNonNull(headers, "Headers must be present");
        Objects.requireNonNull(metadata, "Metadata must be present");

        for (String header : ILLEGAL_METADATA_HEADERS) {
            if (headers.containsKey(header)) {
                String msg = String.format("Critical header [%s] can't be changed", header);
                throw new IllegalArgumentException(msg);
            }
        }

        headers.putAllMetadata(metadata);

        headers.setContentEncoding("chunked");
        HttpContent content = new EmptyContent();
        final GenericUrl genericUrl = new GenericUrl(this.url + formatPath(path));
        return httpPut(genericUrl, headers, content, metadata);
    }

    /**
     * Creates a directory in Manta.
     *
     * @param path The fully qualified path of the Manta directory.
     * @throws IOException                                     If an IO exception has occurred.
     * @throws com.joyent.manta.exception.MantaCryptoException If there's an exception while signing the request.
     * @throws MantaClientHttpResponseException                If a http status code {@literal > 300} is returned.
     */
    public void putDirectory(final String path) throws IOException {
        putDirectory(path, null);
    }


    /**
     * Creates a directory in Manta.
     *
     * @param path    The fully qualified path of the Manta directory.
     * @param headers Optional {@link MantaHttpHeaders}. Consult the Manta api for more header information.
     * @throws IOException                                     If an IO exception has occurred.
     * @throws com.joyent.manta.exception.MantaCryptoException If there's an exception while signing the request.
     * @throws MantaClientHttpResponseException                If a http status code {@literal > 300} is returned.
     */
    public void putDirectory(final String path, final MantaHttpHeaders headers)
            throws IOException {
        Objects.requireNonNull("PUT directory path must be present");

        LOG.debug("PUT    {} [directory]", path);
        final GenericUrl genericUrl = new GenericUrl(this.url + formatPath(path));
        final HttpRequestFactory httpRequestFactory = httpRequestFactoryProvider.getRequestFactory();
        final HttpRequest request = httpRequestFactory.buildPutRequest(genericUrl, new EmptyContent());

        if (headers != null) {
            request.setHeaders(headers.asGoogleClientHttpHeaders());
        }

        request.getHeaders().setContentType(DIRECTORY_REQUEST_CONTENT_TYPE);
        executeAndCloseRequest(request, "PUT    {} response [{}] {} ", path);
    }


    /**
     * Creates a directory in Manta.
     *
     * @param path The fully qualified path of the Manta directory.
     * @param recursive recursive create all of the directories specified in the path
     * @throws IOException If an IO exception has occurred.
     * @throws com.joyent.manta.exception.MantaCryptoException If there's an exception while signing the request.
     * @throws MantaClientHttpResponseException If a http status code {@literal > 300} is returned.
     */
    public void putDirectory(final String path, final boolean recursive)
            throws IOException {
        putDirectory(path, recursive, null);
    }


    /**
     * Creates a directory in Manta.
     *
     * @param path The fully qualified path of the Manta directory.
     * @param recursive recursive create all of the directories specified in the path
     * @param headers Optional {@link MantaHttpHeaders}. Consult the Manta api for more header information.
     * @throws IOException If an IO exception has occurred.
     * @throws com.joyent.manta.exception.MantaCryptoException If there's an exception while signing the request.
     * @throws MantaClientHttpResponseException If a http status code {@literal > 300} is returned.
     */
    public void putDirectory(final String path, final boolean recursive,
                             final MantaHttpHeaders headers)
            throws IOException {
        if (!recursive) {
            putDirectory(path, headers);
            return;
        }

        final String separator = "/";
        final String[] parts = path.split(separator);
        final Iterator<Path> itr = Paths.get("", parts).iterator();
        final StringBuilder sb = new StringBuilder(separator);

        for (int i = 0; itr.hasNext(); i++) {
            final String part = itr.next().toString();
            sb.append(part);

            // This means we aren't in the home nor in the reserved
            // directory path (stor, public, jobs, etc)
            if (i > 1) {
                putDirectory(sb.toString(), headers);
            }

            if (itr.hasNext()) {
                sb.append(separator);
            }
        }
    }


    /**
     * Create a Manta snaplink.
     *
     * @param linkPath The fully qualified path of the new snaplink.
     * @param objectPath The fully qualified path of the object to link against.
     * @param headers Optional {@link MantaHttpHeaders}. Consult the Manta api for more header information.
     * @throws IOException If an IO exception has occurred.
     * @throws com.joyent.manta.exception.MantaCryptoException If there's an exception while signing the request.
     * @throws MantaClientHttpResponseException If a http status code {@literal > 300} is returned.
     */
    public void putSnapLink(final String linkPath, final String objectPath,
                            final MantaHttpHeaders headers)
            throws IOException {
        LOG.debug("PUT    {} -> {} [snaplink]", objectPath, linkPath);
        final GenericUrl genericUrl = new GenericUrl(this.url + formatPath(linkPath));
        final HttpContent content = new EmptyContent();
        final HttpRequestFactory httpRequestFactory = httpRequestFactoryProvider.getRequestFactory();
        final HttpRequest request = httpRequestFactory.buildPutRequest(genericUrl, content);
        if (headers != null) {
            request.setHeaders(headers.asGoogleClientHttpHeaders());
        }

        request.getHeaders().setContentType(LINK_CONTENT_TYPE);
        request.getHeaders().setLocation(formatPath(objectPath));
        executeAndCloseRequest(request, "PUT    {} -> {} response [{}] {} ",
                objectPath, linkPath);
    }

    /*************************************************************************
     * Job Methods
     *************************************************************************/

    /**
     * Submits a new job to be executed. This call is not idempotent, so calling
     * it twice will create two jobs.
     *
     * @param job Object populated with details about the request job
     * @return id of the newly created job
     * @throws IOException thrown when there are problems creating the job over the network
     */
    public UUID createJob(final MantaJob job) throws IOException {
        Objects.requireNonNull(job, "Manta job must be present");

        String path = String.format("%s/jobs", home);
        ObjectMapper mapper = MantaObjectParser.MAPPER;
        byte[] json = mapper.writeValueAsBytes(job);

        HttpContent content = new ByteArrayContent(
                ContentType.APPLICATION_JSON.toString(),
                json);

        LOG.debug("POST   {}", path);

        final GenericUrl genericUrl = new GenericUrl(this.url + formatPath(path));
        final HttpRequestFactory httpRequestFactory = httpRequestFactoryProvider.getRequestFactory();
        final HttpRequest request = httpRequestFactory.buildPostRequest(genericUrl, content);
        request.setContent(content);

        HttpResponse response = executeAndCloseRequest(request,
                "POST   {} response [{}] {} ", path);

        final String location = response.getHeaders().getLocation();
        String id = MantaUtils.lastItemInPath(location);
        return UUID.fromString(id);
    }


    /**
     * Submits inputs to an already created job, as created by createJob().
     * Inputs are object names, and are fed in as a \n separated stream.
     * Inputs will be processed as they are received.
     *
     * @param jobId UUID of the Manta job
     * @param inputs iterator of paths to Manta objects to be added as inputs
     * @throws IOException thrown when we are unable to add inputs over the network
     */
    public void addJobInputs(final UUID jobId,
                             final Iterator<String> inputs) throws IOException {
        Objects.requireNonNull(jobId, "Manta job id must be present");
        Objects.requireNonNull(inputs, "Inputs must be present");

        String contentType = "text/plain; charset=utf-8";
        HttpContent content = new StringIteratorHttpContent(inputs, contentType);

        processJobInputs(jobId, content);
    }


    /**
     * Submits inputs to an already created job, as created by createJob().
     * Inputs are object names, and are fed in as a \n separated stream.
     * Inputs will be processed as they are received.
     *
     * @param jobId UUID of the Manta job
     * @param inputs stream of paths to Manta objects to be added as inputs
     * @throws IOException thrown when we are unable to add inputs over the network
     */
    public void addJobInputs(final UUID jobId,
                             final Stream<String> inputs) throws IOException {
        Objects.requireNonNull(jobId, "Manta job id must be present");
        Objects.requireNonNull(inputs, "Inputs must be present");

        String contentType = "text/plain; charset=utf-8";
        HttpContent content = new StringIteratorHttpContent(inputs, contentType);

        processJobInputs(jobId, content);
    }


    /**
     * Utility method for processing the addition of job inputs over HTTP.
     *
     * @param jobId UUID of the Manta job
     * @param content content object containing input objects
     * @throws IOException thrown when we are unable to add inputs over the network
     */
    protected void processJobInputs(final UUID jobId,
                                    final HttpContent content)
            throws IOException {

        String path = String.format("%s/jobs/%s/live/in", home, jobId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentEncoding("chunked");

        httpPost(path, content, headers);
    }


    /**
     * Get a list of all of the input objects submitted for a job.
     *
     * @param jobId UUID of the Manta job
     * @return List of input objects associated with a job
     * @throws IOException thrown when there is a problem getting objects over the network
     */
    public List<String> getJobInputs(final UUID jobId) throws IOException {
        Objects.requireNonNull(jobId, "Manta job id must not be null");
        String path = String.format("%s/jobs/%s/live/in", home, jobId);

        HttpResponse response = httpGet(path);

        try {
            ArrayList<String> inputs = new ArrayList<>();

            try (InputStream is = response.getContent();
                 Scanner scanner = new Scanner(is, "UTF-8")) {
                while (scanner.hasNextLine()) {
                    inputs.add(scanner.nextLine());
                }
            }

            return Collections.unmodifiableList(inputs);
        } finally {
            response.disconnect();
        }
    }

    /**
     * Submits inputs to an already created job, as created by CreateJob.
     *
     * @param jobId UUID of the Manta job
     * @return true when Manta has accepted the ending input
     * @throws IOException thrown when there is a problem ending input over the network
     */
    public boolean endJobInput(final UUID jobId) throws IOException {
        Objects.requireNonNull(jobId, "Manta job id must not be null");
        String path = String.format("%s/jobs/%s/live/in/end", home, jobId);

        final HttpContent content = new EmptyContent();
        HttpResponse response = httpPost(path, content);

        // We expect a return value of 202 when the cancel request was accepted
        return response.getStatusCode() == HTTP_STATUSCODE_202_ACCEPTED;
    }


    /**
     * <p>This cancels a job from doing any further work. Cancellation is
     * asynchronous and "best effort"; there is no guarantee the job will
     * actually stop. For example, short jobs where input is already
     * closed will likely still run to completion. This is however useful
     * when:</p>
     * <ul>
     * <li>input is still open</li>
     * <li>you have a long-running job</li>
     * </ul>
     * @param jobId UUID of the Manta job
     * @return true when Manta has accepted the cancel request
     * @throws IOException thrown when we have a problem canceling over the network
     */
    public boolean cancelJob(final UUID jobId) throws IOException {
        Objects.requireNonNull(jobId, "Manta job id must not be null");
        String path = String.format("%s/jobs/%s/live/cancel",
                home, jobId);

        final HttpContent content = new EmptyContent();
        HttpResponse response = httpPost(path, content);

        // We expect a return value of 202 when the cancel request was accepted
        return response.getStatusCode() == HTTP_STATUSCODE_202_ACCEPTED;
    }

    /**
     * Gets the high-level job container object for a given id.
     * First, attempts to get the live status object and if it can't be
     * retrieved, then we get the archived status object.
     *
     * @param jobId UUID of the Manta job
     * @return Object representing the properties of the job
     * @throws IOException thrown when we can't get the job over the network
     */
    public MantaJob getJob(final UUID jobId) throws IOException {
        Objects.requireNonNull(jobId, "Manta job id must not be null");
        final String livePath = String.format("%s/jobs/%s/live/status",
                home, jobId);

        MantaJob job;
        HttpResponse response = null;

        try {
            response = httpGet(livePath, OBJECT_PARSER);
            job = response.parseAs(MantaJob.class);
        } catch (MantaClientHttpResponseException e) {
            // If we can't get the live status of the job, we try to get the archived
            // status of the job just like the CLI mjob utility.
            if (e.getServerCode().equals(MantaErrorCode.RESOURCE_NOT_FOUND_ERROR)) {
                final String archivePath = String.format("%s/jobs/%s/job.json",
                        home, jobId);
                response = httpGet(archivePath, OBJECT_PARSER);
                job = response.parseAs(MantaJob.class);
            } else {
                throw e;
            }
        } finally {
            if (response != null) {
                response.disconnect();
            }
        }

        return job;
    }


    /**
     * Gets all of the Manta jobs as a real-time {@link Stream} from
     * the Manta API. Make sure to close this stream when you are done with
     * otherwise the HTTP socket will remain open.
     *
     * @return a stream with all of the jobs
     * @throws IOException thrown when we can't get a list of jobs over the network
     */
    public Stream<MantaJob> getAllJobs() throws IOException {
        return getAllJobs(false);
    }


    /**
     * Gets all of the Manta jobs as a real-time {@link Stream} from
     * the Manta API. Make sure to close this stream when you are done with
     * otherwise the HTTP socket will remain open.
     *
     * @param runningOnly only get the running jobs when true
     * @return a stream with all of the jobs
     * @throws IOException thrown when we can't get a list of jobs over the network
     */
    public Stream<MantaJob> getAllJobs(final boolean runningOnly) throws IOException {
        return getAllJobIds(runningOnly).map(id -> {
            if (id == null) {
                return null;
            }

            try {
                return getJob(id);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }


    /**
     * Gets all of the Manta jobs' IDs as a real-time {@link Stream} from
     * the Manta API. Make sure to close this stream when you are done with
     * otherwise the HTTP socket will remain open.
     *
     * @return a stream with all of the job ids
     * @throws IOException thrown when we can't get a list of jobs over the network
     */
    public Stream<UUID> getAllJobIds() throws IOException {
        return getAllJobIds(false);
    }


    /**
     * Gets all of the Manta jobs' IDs as a real-time {@link Stream} from
     * the Manta API. Make sure to close this stream when you are done with
     * otherwise the HTTP socket will remain open.
     *
     * @param runningOnly only get the running jobs when true
     * @return a stream with all of the job ids
     * @throws IOException thrown when we can't get a list of jobs over the network
     */
    public Stream<UUID> getAllJobIds(final boolean runningOnly) throws IOException {
        final String state = runningOnly ? "?state=running" : "";
        final String path = String.format("%s/jobs", home, state);

        final GenericUrl genericUrl = new GenericUrl(this.url + formatPath(path) +
            state);

        final HttpResponse response = httpGet(genericUrl, null);

        final ObjectMapper mapper = MantaObjectParser.MAPPER;
        final Reader reader = new InputStreamReader(response.getContent());
        final BufferedReader br = new BufferedReader(reader);

        return br.lines().map(s -> {
            try {
                final Map jobDetails = mapper.readValue(s, Map.class);
                final Object value = jobDetails.get("name");

                if (value == null) {
                    return null;
                }

                return UUID.fromString(value.toString());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).onClose(() -> {
            try {
                br.close();
                response.disconnect();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }


    /**
     * Finds the content type set in {@link MantaHttpHeaders} and returns that if it
     * is not null. Otherwise, it will return the specified default content type.
     *
     * @param headers headers to parse for content type
     * @param defaultContentType content type to default to
     * @return content type as string
     */
    protected static String findOrDefaultContentType(final MantaHttpHeaders headers,
                                                     final String defaultContentType) {
        final String contentType;

        if (headers == null || headers.getContentType() == null) {
            contentType = defaultContentType;
        } else {
            contentType = headers.getContentType();
        }

        return contentType;
    }


    /**
     * Utility method for handling HTTP POST to the Google HTTP Client.
     *
     * @param path path to post to (without hostname)
     * @param content content object to post
     * @return HTTP response object
     * @throws IOException thrown when there is a problem POSTing over the network
     */
    protected HttpResponse httpPost(final String path,
                                    final HttpContent content) throws IOException {
        return httpPost(path, content, null);
    }


    /**
     * Utility method for handling HTTP POST to the Google HTTP Client.
     *
     * @param path path to post to (without hostname)
     * @param content content object to post
     * @param headers HTTP headers to attach to request
     * @return HTTP response object
     * @throws IOException thrown when there is a problem POSTing over the network
     */
    protected HttpResponse httpPost(final String path,
                                    final HttpContent content,
                                    final HttpHeaders headers) throws IOException {
        LOG.debug("POST   {}", path);

        final GenericUrl genericUrl = new GenericUrl(this.url + formatPath(path));
        final HttpRequestFactory httpRequestFactory = httpRequestFactoryProvider.getRequestFactory();
        final HttpRequest request = httpRequestFactory.buildPostRequest(genericUrl, content);

        if (content != null) {
            request.setContent(content);
        }

        if (headers != null) {
            request.setHeaders(headers);
        }

        return executeAndCloseRequest(request,
                "POST   {} response [{}] {} ", path);
    }


    /**
     * Executes a {@link HttpRequest}, logs the request and returns back the
     * response.
     *
     * @param request request object
     * @param logMessage log message associated with request that must contain
     *                   a substitution placeholder for status code and
     *                   status message
     * @param logParameters additional log placeholders
     * @return response object
     * @throws IOException thrown when we are unable to process the request on the network
     */
    protected HttpResponse executeAndCloseRequest(final HttpRequest request,
                                                  final String logMessage,
                                                  final Object... logParameters)
            throws IOException {
        HttpResponse response = null;

        try {
            response = request.execute();
            LOG.debug(logMessage, logParameters, response.getStatusCode(),
                      response.getStatusMessage());

            return response;
        } catch (final HttpResponseException e) {
            throw new MantaClientHttpResponseException(e);
        } finally {
            if (response != null) {
                response.disconnect();
            }
        }
    }


    @Override
    public void close() throws Exception {
        this.httpRequestFactoryProvider.close();
    }

    /**
     * Closes the Manta client resource and logs any problems to the debug
     * logger. No exceptions are thrown on failure.
     */
    public void closeQuietly() {
        try {
            close();
        } catch (Exception e) {
            LOG.debug("Error closing connection", e);
        }
    }
}
