package org.ocsinventory.android.actions;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.entity.FileEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParams;
import org.apache.http.protocol.HTTP;
import org.apache.http.util.EntityUtils;
import org.ocsinventory.android.agent.R;

import android.content.Context;

public class OCSProtocol {
	private OCSLog ocslog = OCSLog.getInstance();
	private OCSFiles ocsfile;
	private String http_agent;

	public OCSProtocol(Context context) {
		ocsfile = new OCSFiles(context);
		http_agent=context.getString(R.string.useragent);
	}

	public String sendPrologueMessage(Inventory inv) throws OCSProtocolException {
		ocslog.append("Start Sending Prolog...");
		String repMsg;
		String reponse;
		File localFile = ocsfile.getPrologFileXML();
		String sURL = OCSSettings.getInstance().getServerUrl();
		// boolean gz = OCSSettings.getInstance().getGzip();

		repMsg = sendmethod(localFile, sURL, true);
		ocslog.append("Finnish Sending Prolog...");
		String freq=extractFreq(repMsg);
		if  ( freq.length() > 0 )
			OCSSettings.getInstance().setFreqMaj(freq);

		reponse = extractResponse(repMsg);
		return reponse;
	}

	public String sendInventoryMessage(Inventory inventory) throws OCSProtocolException {
		ocslog.append("Start Sending Inventory...");
		String retour = null;		
		// boolean gz = OCSSettings.getInstance().getGzip();

		File invFile = ocsfile.getInventoryFileXML(inventory);
		String sURL = OCSSettings.getInstance().getServerUrl();

		String repMsg  = sendmethod(invFile, sURL, true);

		retour = extractResponse(repMsg);
		ocslog.append("Finnish Sending Inventory...");
		// upload ok. Save current sections fingerprints values
		inventory.saveSectionsFP();
		invFile.delete();
		return retour;
	}

	public DefaultHttpClient getNewHttpClient(boolean strict ) {
		try {
			SSLSocketFactory sf;
			if ( strict ) {
				sf = SSLSocketFactory.getSocketFactory();
			}
			else {
				android.util.Log.d("X509", "cool");
				KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
				trustStore.load(null, null);
				sf = new CoolSSLSocketFactory(trustStore);
			}

			HttpParams params = new BasicHttpParams();
			HttpProtocolParams.setVersion(params, HttpVersion.HTTP_1_1);
			HttpProtocolParams.setContentCharset(params, HTTP.UTF_8);

			SchemeRegistry registry = new SchemeRegistry();
			registry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), 80));
			registry.register(new Scheme("https", sf, 443));

			ClientConnectionManager ccm = new ThreadSafeClientConnManager(params, registry);

			HttpConnectionParams.setConnectionTimeout(params, 10000);
			HttpConnectionParams.setSoTimeout(params, 10000);

			return new DefaultHttpClient(ccm, params);
		} catch (Exception e) {
			return new DefaultHttpClient();
		}
	}

	public String sendmethod(File pFile, String server, boolean gziped)	throws OCSProtocolException {
		OCSLog ocslog = OCSLog.getInstance();
		OCSSettings ocssettings = OCSSettings.getInstance();
		ocslog.append("Start send method");
		String retour;

		HttpPost httppost = null;

		try {
			httppost = new HttpPost(server);
		} catch ( IllegalArgumentException e ) {
			ocslog.append(e.getMessage());
			throw new OCSProtocolException("Incorect serveur URL");
		}



		// FileEntity localFileEntity = new FileEntity(paramFile, "application/x-compress; charset=\"UTF-8\"");

		File fileToPost;
		if ( gziped ) {
			ocslog.append("Start compression");
			fileToPost=ocsfile.getGzipedFile(pFile);
			if ( fileToPost == null )
				throw new OCSProtocolException("Error during temp file creation");
			ocslog.append("Compression done");
		}
		else
			fileToPost=pFile;

		FileEntity fileEntity = new FileEntity(fileToPost, "text/plain; charset=\"UTF-8\"");
		httppost.setEntity(fileEntity);
		httppost.setHeader("User-Agent", http_agent);
		if ( gziped ) {
			httppost.setHeader("Content-Encoding", "gzip");
		}


		DefaultHttpClient httpClient = getNewHttpClient(OCSSettings.getInstance().isSSLStrict());


		if  ( ocssettings.isProxy()) {
			ocslog.append("Use proxy : " + ocssettings.getProxyAdr());
			HttpHost proxy = new HttpHost(ocssettings.getProxyAdr(), ocssettings.getProxyPort());
			httpClient.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY, proxy);	       
		}
		if  ( ocssettings.isAuth()) {
			ocslog.append("Use AUTH : " + ocssettings.getLogin()+"/*****");
			/*
			CredentialsProvider credProvider = new BasicCredentialsProvider();
	        credProvider.setCredentials(new AuthScope(AuthScope.ANY_HOST, AuthScope.ANY_PORT),
	            new UsernamePasswordCredentials(ocssettings.getLogin(), ocssettings.getPasswd()));
			 */
			UsernamePasswordCredentials  creds = new UsernamePasswordCredentials(ocssettings.getLogin(), ocssettings.getPasswd());
			ocslog.append(creds.toString());
			httpClient.getCredentialsProvider().setCredentials(
					new AuthScope(AuthScope.ANY_HOST, AuthScope.ANY_PORT),
					creds);
		}


		ocslog.append("Call : " + server);
		HttpResponse localHttpResponse=null;
		try {
			localHttpResponse = httpClient.execute(httppost);
		} catch (ClientProtocolException e) {
			throw new OCSProtocolException(e.getMessage());
		} catch (IOException e) {
			throw new OCSProtocolException("Cant connect to "+e.getMessage());
		}
		int httpCode = localHttpResponse.getStatusLine().getStatusCode();
		ocslog.append("Response status code : " + String.valueOf(httpCode));

		try {
			if ( httpCode== 200) {
				if  ( gziped ) {
					InputStream is = localHttpResponse.getEntity().getContent();
					GZIPInputStream gzis = new GZIPInputStream(is);
					ByteArrayOutputStream baos = new ByteArrayOutputStream();
					byte[] buff = new byte[128];
					int n;
					while ( (n = gzis.read(buff,0, buff.length)) > 0)  {
						baos.write(buff,0,n);
					}
					retour=baos.toString();
				} else
					retour=EntityUtils.toString(localHttpResponse.getEntity());

			} else {
				ocslog.append("***Server communication error: ");
				throw new OCSProtocolException("Http communication error code "+String.valueOf(httpCode));
			}
			ocslog.append("Finnish send method");

		} catch (IOException localIOException) {
			String msg = localIOException.getMessage();
			ocslog.append(msg);
			throw new OCSProtocolException(msg);
		}
		if ( gziped )
			fileToPost.delete();
		return retour;
	}

	private String extractResponse( String message ) {
		String resp = "";
		Pattern p = Pattern.compile(".*<RESPONSE>(.*)</RESPONSE>.*", Pattern.CASE_INSENSITIVE);
		Matcher m= p.matcher(message);
		if ( m.find() ) 
			return m.group(1);
		return resp;
	}
	private String extractFreq( String message ) {
		String resp = "";
		Pattern p = Pattern.compile(".*<PROLOG_FREQ>(.*)</PROLOG_FREQ>.*", Pattern.CASE_INSENSITIVE);
		Matcher m= p.matcher(message);
		if ( m.find() ) 
			return m.group(1);
		return resp;
	}
}