/*
The MIT License

Copyright for portions of OpenUnirest/uniresr-java are held by Mashape (c) 2013 as part of Kong/unirest-java.All other copyright for OpenUnirest/unirest-java are held by OpenUnirest (c) 2018.

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject to
the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package io.github.openunirest.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;

import io.github.openunirest.http.async.CallbackFuture;
import org.apache.http.HttpEntity;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpOptions;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.nio.entity.NByteArrayEntity;

import io.github.openunirest.http.async.Callback;
import io.github.openunirest.http.async.utils.AsyncIdleConnectionMonitorThread;
import io.github.openunirest.http.exceptions.UnirestException;
import io.github.openunirest.http.options.Option;
import io.github.openunirest.http.options.Options;
import io.github.openunirest.http.utils.ClientFactory;
import io.github.openunirest.request.HttpRequest;

public class HttpClientHelper {

	private static final String CONTENT_TYPE = "content-type";
	private static final String ACCEPT_ENCODING_HEADER = "accept-encoding";
	private static final String USER_AGENT_HEADER = "user-agent";
	private static final String USER_AGENT = "unirest-java/1.3.11";
	private static final UriFormatter uriFormatter = new UriFormatter();

	public static <T> CompletableFuture<HttpResponse<T>> requestAsync(HttpRequest request, final Class<T> responseClass) {
		return requestAsync(request, responseClass, new CompletableFuture<>());
	}

	public static <T> CompletableFuture<HttpResponse<T>> requestAsync(HttpRequest request, final Class<T> responseClass, Callback<T> callback) {
		return requestAsync(request, responseClass, CallbackFuture.wrap(callback));
	}

	public static <T> CompletableFuture<HttpResponse<T>> requestAsync(HttpRequest request, final Class<T> responseClass, CompletableFuture<HttpResponse<T>> callback) {
		Objects.requireNonNull(callback);

		HttpUriRequest requestObj = prepareRequest(request, true);

		asyncClient()
				.execute(requestObj, new FutureCallback<org.apache.http.HttpResponse>() {
					@Override
					public void completed(org.apache.http.HttpResponse httpResponse) {
						callback.complete(new HttpResponse<>(httpResponse, responseClass));
					}

					@Override
					public void failed(Exception e) {
						callback.completeExceptionally(e);
					}

					@Override
					public void cancelled() {
						callback.completeExceptionally(new UnirestException("canceled"));
					}
				});
		return callback;
	}

	private static CloseableHttpAsyncClient asyncClient() {
		CloseableHttpAsyncClient asyncHttpClient = ClientFactory.getAsyncHttpClient();
		if (!asyncHttpClient.isRunning()) {
			asyncHttpClient.start();
			AsyncIdleConnectionMonitorThread asyncIdleConnectionMonitorThread = (AsyncIdleConnectionMonitorThread) Options.getOption(Option.ASYNC_MONITOR);
			asyncIdleConnectionMonitorThread.start();
		}
		return asyncHttpClient;
	}

	public static <T> HttpResponse<T> request(HttpRequest request, Class<T> responseClass) throws UnirestException {
		HttpRequestBase requestObj = prepareRequest(request, false);
		HttpClient client = ClientFactory.getHttpClient(); // The
															// DefaultHttpClient
															// is thread-safe

		org.apache.http.HttpResponse response;
		try {
			response = client.execute(requestObj);
			HttpResponse<T> httpResponse = new HttpResponse<T>(response, responseClass);
			requestObj.releaseConnection();
			return httpResponse;
		} catch (Exception e) {
			throw new UnirestException(e);
		} finally {
			requestObj.releaseConnection();
		}
	}

	private static HttpRequestBase prepareRequest(HttpRequest request, boolean async) {

		Object defaultHeaders = Options.getOption(Option.DEFAULT_HEADERS);
		if (defaultHeaders != null) {
			@SuppressWarnings("unchecked")
			Set<Entry<String, String>> entrySet = ((Map<String, String>) defaultHeaders).entrySet();
			for (Entry<String, String> entry : entrySet) {
				request.header(entry.getKey(), entry.getValue());
			}
		}

		if (!request.getHeaders().containsKey(USER_AGENT_HEADER)) {
			request.header(USER_AGENT_HEADER, USER_AGENT);
		}
		if (!request.getHeaders().containsKey(ACCEPT_ENCODING_HEADER)) {
			request.header(ACCEPT_ENCODING_HEADER, "gzip");
		}

		HttpRequestBase reqObj = null;

		String urlToRequest = uriFormatter.apply(request);

		switch (request.getHttpMethod()) {
		case GET:
			reqObj = new HttpGet(urlToRequest);
			break;
		case POST:
			reqObj = new HttpPost(urlToRequest);
			break;
		case PUT:
			reqObj = new HttpPut(urlToRequest);
			break;
		case DELETE:
			reqObj = new HttpDeleteWithBody(urlToRequest);
			break;
		case PATCH:
			reqObj = new HttpPatchWithBody(urlToRequest);
			break;
		case OPTIONS:
			reqObj = new HttpOptions(urlToRequest);
			break;
		case HEAD:
			reqObj = new HttpHead(urlToRequest);
			break;
		}

		Set<Entry<String, List<String>>> entrySet = request.getHeaders().entrySet();
		for (Entry<String, List<String>> entry : entrySet) {
			List<String> values = entry.getValue();
			if (values != null) {
				for (String value : values) {
					reqObj.addHeader(entry.getKey(), value);
				}
			}
		}

		// Set body
		if (!(request.getHttpMethod() == HttpMethod.GET || request.getHttpMethod() == HttpMethod.HEAD)) {
			if (request.getBody() != null) {
				HttpEntity entity = request.getBody().getEntity();
				if (async) {
					if (reqObj.getHeaders(CONTENT_TYPE) == null || reqObj.getHeaders(CONTENT_TYPE).length == 0) {
						reqObj.setHeader(entity.getContentType());
					}
					try {
						ByteArrayOutputStream output = new ByteArrayOutputStream();
						entity.writeTo(output);
						NByteArrayEntity en = new NByteArrayEntity(output.toByteArray());
						((HttpEntityEnclosingRequestBase) reqObj).setEntity(en);
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				} else {
					((HttpEntityEnclosingRequestBase) reqObj).setEntity(entity);
				}
			}
		}

		return reqObj;
	}

}