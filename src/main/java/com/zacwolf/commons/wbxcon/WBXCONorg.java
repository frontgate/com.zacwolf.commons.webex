/* @(#)WBXCONexception.java - zac@zacwolf.com
 *
 * WebEx Connect REST webservice Delegate Controller which creates, manages and audits WBX REST API Calls for WebEx Connect.
 * 
	Licensed under the MIT License (MIT)
	
	Copyright (c) 2014 Zac Morris
	
	Permission is hereby granted, free of charge, to any person obtaining a copy
	of this software and associated documentation files (the "Software"), to deal
	in the Software without restriction, including without limitation the rights
	to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
	copies of the Software, and to permit persons to whom the Software is
	furnished to do so, subject to the following conditions:
	
	The above copyright notice and this permission notice shall be included in
	all copies or substantial portions of the Software.
	
	THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
	IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
	FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
	AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
	LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
	OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
	THE SOFTWARE.
 */
package com.zacwolf.commons.wbxcon;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.SocketException;
import java.security.KeyStore;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.commons.io.IOUtils;
import org.apache.http.NameValuePair;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLContexts;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.BasicHttpContext;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;

import com.zacwolf.commons.wbxcon.exceptions.WBXCONexception;

/**
 * This class contains all of the actual WebEx Connect REST API interactions.<br /><br />
 * <b>
 * If the class is not able to establish an HTTPS client, this class exits the JVM with error code <code>2</code><br />
 * If the class is ever not able to generate a CRED, this class exits the JVM with error code <code>3</code>
 * </b>
 */
final class WBXCONorg {

final		private	static		int						CRED_TIMEOUT		=	100;	// "cred" stays active for two hours but use 100 minutes to be safe
final		private	static		int						HTTP_TIMEOUT		=	10;		// Don't allow any http request to take longer than 10 minutes
final		private	static		int						MAX_HTTP_REQUESTS	=	10;		// Number of active http calls open at any give time
final		private	static		String					TRUSTSTOREFILENAME	=	"webexconnect.truststore";
final		private	static		String					TRUSTSTOREPASS		=	"wbxcon";
final		private	static		ExecutorService			THREADPOOL			=	Executors.newFixedThreadPool(MAX_HTTP_REQUESTS, new MyThreadFactory("WAPIWORKERS") );
volatile	private	static		CloseableHttpClient		HTTPSCLIENT			=	null;

final		private	transient	String					wapiUSER;
final		private	transient	String					wapiPASS;
final		private	transient	WBXCONuser				wapiUser;
volatile	private	transient	String					wapiURL				=	null;
volatile	private	transient	String					wapiREPORTURL		=	null;
volatile	private	transient	String					wapiAUTHURL			=	"https://loginp.webexconnect.com/cas/auth.do";


final				transient	String					orgName;
final				transient	String					orgID;
final				transient	String					namespaceID;
final				transient	PWSRule					passwordrule;

volatile	private	transient	String					cred				=	null;
volatile	private	transient	long					cred_generated		=	0;

	public WBXCONorg(final String domain_name, final String wapiUSER, final String wapiPASS) throws WBXCONexception {
		this(domain_name,null,wapiUSER,wapiPASS);
	}

	/**
	 * Class <code>Contructor</code> initializes WBXCONorg instance for the given managed org (domain) instance.
	 * As part of initialization the Constructor makes a call to establish orgID and namespaceID for the domain.
	 * 
	 * The REST API calls are made via https GET and POST.  As such, the <code>HTTPSCLIENT</code> needs to be
	 * initialized via a certificate stored in a default keystore.  Since the keystore contains a "static"
	 * certificate provided by WebEx Connect, the keystore is generated "in source".  If WebEx Connect modifies
	 * their default https certificate, you will need to download the latest version of this package from:<br />
	 * <br />
	 * <a href="https://github.com/ZacWolf/com.zacwolf.commons">https://github.com/ZacWolf/com.zacwolf.commons</a>
	 * 
	 * 
	 * Whatever user is specified for wapiUSER, the following special privileges need to be granted to the account:
	 * 
	 * WBX:ManageDomain
	 * WBX:ManageUsers
	 * WBX:ManageRoles
	 * 
	 * @param domain_name	Name of the WebEx Connect Managed Org
	 * @param wapiAUTHURL	(optional) URL used to override the default URL used to generate the initial login token
	 * @param wapiUSER		WebEx UserName to use in making the REST calls
	 * @param wapiPASS		WebEx user password to use in making the REST calls
	 * @throws WBXCONexception
	 */
	WBXCONorg(final String domain_name, final String wapiAUTHURL, final String wapiUSER, final String wapiPASS) throws WBXCONexception {
		if (HTTPSCLIENT==null)
		try{
			//Quiet the various apache http client loggers
			Logger.getLogger("org.apache.http").setLevel(Level.SEVERE);
			Logger.getLogger("org.apache.http.wire").setLevel(Level.SEVERE);
			Logger.getLogger("org.apache.http.headers").setLevel(Level.SEVERE);
			System.setProperty("org.apache.commons.logging.Log", "org.apache.commons.logging.impl.SimpleLog");
			System.setProperty("org.apache.commons.logging.simplelog.showdatetime", "true");
			System.setProperty("org.apache.commons.logging.simplelog.log.httpclient.wire", "ERROR");
			System.setProperty("org.apache.commons.logging.simplelog.log.org.apache.http", "ERROR");
			System.setProperty("org.apache.commons.logging.simplelog.log.org.apache.http.headers", "ERROR");
			
final	PoolingHttpClientConnectionManager	cm			=	new PoolingHttpClientConnectionManager();
											cm.setMaxTotal(MAX_HTTP_REQUESTS);
final	KeyStore							trustStore	=	KeyStore.getInstance("JCEKS");
															// Use the default keystore that is in the same package directory
final	InputStream 						instream	=	WBXCONorg.class.getClassLoader().getResourceAsStream(WBXCONorg.class.getPackage().getName().replaceAll("\\.", "/")+"/"+TRUSTSTOREFILENAME);
			try {							trustStore.load(instream, TRUSTSTOREPASS.toCharArray());
			} finally {						instream.close();
			}
final	SSLContext							sslcontext	=	SSLContexts .custom()// Trust own CA and all self-signed certs
																		.loadTrustMaterial(trustStore, new TrustSelfSignedStrategy())
																		.build();
final	SSLConnectionSocketFactory 			sslsf		=	new SSLConnectionSocketFactory(
																sslcontext,
																new String[] { "TLSv1" },// Allow TLSv1 protocol only
																null,
																SSLConnectionSocketFactory.BROWSER_COMPATIBLE_HOSTNAME_VERIFIER
															);
final	RequestConfig						config		=	RequestConfig.custom()
																		 .setConnectTimeout(HTTP_TIMEOUT * 60000)
																		 .setConnectionRequestTimeout(HTTP_TIMEOUT * 60000)
																		 .setSocketTimeout(HTTP_TIMEOUT * 60000)
																		 .build();
											HTTPSCLIENT	=	HttpClients	.custom()
																		.setConnectionManager(cm)
																		.setSSLSocketFactory(sslsf)
																		.setDefaultRequestConfig(config)
																		.build();
		} catch (final Exception e){
			System.err.println(WBXCONorg.class.getCanonicalName()+" UNABLE TO ESTABLISH HTTPSCLIENT FOR WAPI CALLS. All WAPI CALLS WILL FAIL!!!");
			e.printStackTrace();
			//System.exit(2);
		}
		Runtime.getRuntime().addShutdownHook(new Thread("WBXCONorg shutdownhook") {
			@Override
			public void run() {
				try { finalize();
				} catch (final Throwable e) {
					e.printStackTrace();
				}
			}
		});
		this.orgName		=	domain_name;
		this.wapiAUTHURL	=	wapiAUTHURL!=null?wapiAUTHURL:this.wapiAUTHURL;
		this.wapiUSER		=	wapiUSER+(!wapiUSER.endsWith("@"+domain_name)?"@"+domain_name:"");
		this.wapiPASS		=	wapiPASS;

final	Document				dom;
		try{
			System.out.println("===============  1");
final	DocumentBuilderFactory	factory		=	DocumentBuilderFactory.newInstance();
System.out.println("===============  2");
								factory.setValidating(false);
								System.out.println("===============  3");
								factory.setCoalescing(true);
								System.out.println("===============  4");
final	DocumentBuilder			db			=	factory.newDocumentBuilder();
System.out.println("===============  5");
final	List<NameValuePair>		params		=	new ArrayList<NameValuePair>();
System.out.println("===============  6");
								params.add(new BasicNameValuePair("cmd","get"));
								System.out.println("===============  7");
								params.add(new BasicNameValuePair("type","org"));
								System.out.println("===============  8");
								params.add(new BasicNameValuePair("select","org/orgID:/org/namespaceID:ext/WBX/PWSRule"));
								System.out.println("===============  9");
								params.add(new BasicNameValuePair("id", "current"));
								System.out.println("===============  10");
								System.out.println("===============  getDomainCredToken() :"+getDomainCredToken());
								params.add(new BasicNameValuePair("cred",getDomainCredToken()));
								System.out.println("===============  11");
								System.out.println("===============  params"+params.toString());
								System.out.println("===============Before wapiURL :"+this.wapiURL);
final	HttpPost 				httpPost	=	new HttpPost(this.wapiURL);
System.out.println("=============== after wapiURL :"+this.wapiURL);

								httpPost.setEntity(new UrlEncodedFormEntity(params, org.apache.http.Consts.UTF_8));
								System.out.println("===============  12");
final	CloseableHttpResponse	httpRes		=	HTTPSCLIENT.execute(httpPost,new BasicHttpContext());
System.out.println("===============  13");

if(httpRes == null){
	System.out.println("===============  httpRes is NULL");
}

			try{				dom			=	db.parse(httpRes.getEntity().getContent());
			System.out.println("===============  14");
			}finally{			httpRes.close();
			}
		} catch (final Exception e){
			throw new WBXCONexception(e);
		}
		final	NodeList				result	=	dom.getElementsByTagName("result");
			if (result==null || result.item(0)==null || !result.item(0).getTextContent().equalsIgnoreCase("success"))
				throw new WBXCONexception("ERROR::WBXCONorg:constructor(\""+domain_name+"\")::"+documentGetErrorString(dom));
		
		this.orgID			=	dom.getElementsByTagName("orgID").item(0).getTextContent();
		this.namespaceID	=	dom.getElementsByTagName("namespaceID").item(0).getTextContent();
		this.passwordrule	=	new PWSRule(Integer.parseInt(documentGetTextContentByTagName(dom,"PWMinimumLength_9")),
											Integer.parseInt(documentGetTextContentByTagName(dom,"PWMinimumAlpha_9")),
											Integer.parseInt(documentGetTextContentByTagName(dom,"PWMinimumNumeric_9")),
											Integer.parseInt(documentGetTextContentByTagName(dom,"PWMinimumSpecial_9")),
											documentGetTextContentByTagName(dom,"PWRequireMixedCase_B").equalsIgnoreCase("true")
											);
		this.wapiUser		=	restapiAccountGet(this.wapiUSER);
	}

	/**
	 * Gracefully Shutdown any Active WAPI calls
	 */
	@Override
	public final void finalize(){
		THREADPOOL.shutdownNow();
		try {	HTTPSCLIENT.close();
		} catch (final IOException e){}
	}

	/**
	 * Call to override the default URL for the reporting servlet
	 * default: "https://swapi.webexconnect.com/wbxconnect/getfile.do"
	 * 
	 * @param wapiREPORTURL
	 */
	final void domainOverrideDefaultReportURL(final String wapiREPORTURL){
		if (wapiREPORTURL==null)
			throw new NullPointerException("wapiREPORTURL may not be null");
		this.wapiREPORTURL	=	wapiREPORTURL;
	}

	/**
	 * Call to override the default URL for the loginp auth servlet
	 * default: "https://loginp.webexconnect.com/cas/auth.do"
	 * 
	 * @param wapiREPORTURL
	 */
	final void domainOverrideDefaultAuthURL(final String wapiAUTHURL){
		if (wapiAUTHURL==null)
			throw new NullPointerException("wapiAUTHURL may not be null");
		this.wapiAUTHURL	=	wapiAUTHURL;
	}

	/**
	 * Provides a cred.  If the cred is null or old, this method generates a new cred.
	 * @return a new cred
	 * @throws WBXCONexception
	 */
	final String getDomainCredToken() throws WBXCONexception{
		if (this.cred==null || this.cred_generated<System.currentTimeMillis()-(60000*CRED_TIMEOUT))
			restapiDomainGetCredToken(0);
		return this.cred;
	}

	/**
	 * Authenticates with the wapiAUTHURL, to pull a token and a server name
	 * which is then used to populate the wapiURL and generate a cred used
	 * by all subsequent REST calls
	 * 
	 * These queries are not added to the WAPIworker thread pool queue
	 * 
	 * @throws WBXCONexception
	 */
	final private synchronized void restapiDomainGetCredToken (final int retry) {
		Document				dom			=	null;
final	List<NameValuePair>		params		=	new ArrayList<NameValuePair>();
								params.add(new BasicNameValuePair("cmd","getwebextoken"));
								params.add(new BasicNameValuePair("username",this.wapiUSER));
								params.add(new BasicNameValuePair("password",this.wapiPASS));
								params.add(new BasicNameValuePair("isp","wbx"));
		try{
final	DocumentBuilderFactory	factory 	=	DocumentBuilderFactory.newInstance();
								factory.setValidating(false);
								factory.setCoalescing(true);
final	DocumentBuilder			db			=	factory.newDocumentBuilder();

System.out.println("this.wapiAUTHURL : "+this.wapiAUTHURL);
System.out.println("restapiDomainGetCredToken: 1 ");

		HttpPost 				httpPost	=	new HttpPost(this.wapiAUTHURL);
		System.out.println("restapiDomainGetCredToken: 2 ");
								httpPost.setEntity(new UrlEncodedFormEntity(params, org.apache.http.Consts.UTF_8));
								System.out.println("restapiDomainGetCredToken: 3 ");
		CloseableHttpResponse	httpRes		=	HTTPSCLIENT.execute(httpPost,new BasicHttpContext());
		System.out.println("restapiDomainGetCredToken: 4 ");
			try{				dom			=	db.parse(httpRes.getEntity().getContent());
			System.out.println("restapiDomainGetCredToken: 5 ");
			}finally{			httpRes.close();
			System.out.println("restapiDomainGetCredToken: 6 ");
			}
		NodeList				result		=	dom.getElementsByTagName("result");
		System.out.println("restapiDomainGetCredToken: 7 ");
			if (result==null || result.item(0)==null || !result.item(0).getTextContent().equalsIgnoreCase("success"))
				throw new WBXCONexception("restapiDomainGetCredToken("+retry+"): [RESULT]:"+result.item(0).getTextContent()+" [ERROR}:"+documentGetErrorString(dom));
			System.out.println("restapiDomainGetCredToken: 8 ");
								this.wapiURL		=	"https://"+dom.getElementsByTagName("serviceurl").item(0).getTextContent()+"/op.do";
								System.out.println("=======restapiDomainGetCredToken======= wapiURL :"+this.wapiURL);
								this.wapiREPORTURL	=	"https://"+dom.getElementsByTagName("serviceurl").item(0).getTextContent()+"/getfile.do";
								System.out.println("=======restapiDomainGetCredToken======= wapiREPORTURL :"+this.wapiREPORTURL);
								params.clear();
								params.add(new BasicNameValuePair("cmd","login"));
								params.add(new BasicNameValuePair("username",this.wapiUSER));
								params.add(new BasicNameValuePair("isp","wbx"));
								params.add(new BasicNameValuePair("autocommit","true"));
								params.add(new BasicNameValuePair("token",dom.getElementsByTagName("token").item(0).getTextContent()));
								httpPost	=	new HttpPost(this.wapiURL);
								System.out.println("restapiDomainGetCredToken: 9 ");
								httpPost.setEntity(new UrlEncodedFormEntity(params, org.apache.http.Consts.UTF_8));
								System.out.println("restapiDomainGetCredToken: 10 ");
								httpRes		=	HTTPSCLIENT.execute(httpPost,new BasicHttpContext());
								System.out.println("restapiDomainGetCredToken: 11 ");
			try{				dom			=	db.parse(httpRes.getEntity().getContent());
			System.out.println("restapiDomainGetCredToken: 12 ");
			}finally{			httpRes.close();
			}					result		=	dom.getElementsByTagName("result");
			if (result==null || result.item(0)==null || !result.item(0).getTextContent().equalsIgnoreCase("success"))
				throw new WBXCONexception(getMethodName(2)+": [RESULT]:"+result.item(0).getTextContent()+" [ERROR}:"+documentGetErrorString(dom));
			System.out.println("restapiDomainGetCredToken: 13 ");
								this.cred			=	dom.getElementsByTagName("cred").item(0).getTextContent();
								System.out.println("restapiDomainGetCredToken: 14 ");
								this.cred_generated	=	System.currentTimeMillis();
								System.out.println("restapiDomainGetCredToken: 15 ");
		} catch (final Exception e){
			if (retry<3){
				//Retry generating a new cred three times before giving up
				restapiDomainGetCredToken(retry+1);
			} else {
				this.wapiURL	=	null;
				System.err.println(getMethodName()+"("+retry+") for "+this.orgName+" unable to generate CRED token.");
				e.printStackTrace();
				//System.exit(3);
			}
		}
	}

	final private void cleanCred(final List<NameValuePair> params) throws WBXCONexception{
		for (final NameValuePair pair:params){
			if (pair.getName().equalsIgnoreCase("cred"))
				params.remove(pair);
		}
		params.add(new BasicNameValuePair("cred",getDomainCredToken()));
	}

	/**
	 * Private method to execute a REST query in a real-time non-queued way
	 * 
	 * @param params
	 * @return Document xml output from the REST API call
	 * @throws WBXCONexception
	 */
	@SuppressWarnings("unused")
	final private Document executeNonQueued(final List<NameValuePair> params) throws WBXCONexception{
		return executeNonQueued(params,0);
	}
	
	final private Document executeNonQueued(final List<NameValuePair> params,final int retry) throws WBXCONexception{
		Document				dom			=	null;
		try{					cleanCred(params);
final	DocumentBuilderFactory	factory 	=	DocumentBuilderFactory.newInstance();
								factory.setValidating(false);
								factory.setCoalescing(true);
final	DocumentBuilder			db			=	factory.newDocumentBuilder();
final	HttpPost 				httpPost	=	new HttpPost(this.wapiURL);
								httpPost.setEntity(new UrlEncodedFormEntity(params, org.apache.http.Consts.UTF_8));
final	CloseableHttpResponse	httpRes		=	HTTPSCLIENT.execute(httpPost,new BasicHttpContext());
			try{				dom			=	db.parse(httpRes.getEntity().getContent());
			}finally{			httpRes.close();
			}
		} catch (final SocketException se){
			if (retry<3){//Catches issues with WebEx Connect server connection
				System.err.println("SocketException making wapi call. Retry:"+(retry+1));
				return executeNonQueued(params, retry+1);
			}else
				throw new WBXCONexception(se);
		} catch (final Exception e){
			throw new WBXCONexception(e);
		}
		if (dom!=null){
final	NodeList				result		=	dom.getElementsByTagName("result");
			if (result==null || result.item(0)==null || !result.item(0).getTextContent().equalsIgnoreCase("success"))
				throw new WBXCONexception(getMethodName(2)+": [RESULT]:"+result.item(0).getTextContent()+" [ERROR}:"+documentGetErrorString(dom));
		}
		return dom;
	}

	/**
	 * Private method to add a REST Query to the thread pool queue.
	 * The method still <i>blocks</i>, but the thread pool queue
	 * limits how many queries can be active at any time.
	 * 
	 * @param params
	 * @return Document xml output from the REST API call
	 * @throws WBXCONexception
	 */
	final private Document executeQueued(final List<NameValuePair> params) throws WBXCONexception{
		return executeQueued(params,0);
	}
	
	final private Document executeQueued(final List<NameValuePair> params,final int retry) throws WBXCONexception{
		try{
			return THREADPOOL.submit(new Callable<Document>(){
				@Override
				public Document call() throws WBXCONexception {
					return getDoc(0);
				}
				
				private Document getDoc(final int retry) throws WBXCONexception{
			Document				dom			=	null;
					try{			cleanCred(params);
	final	DocumentBuilderFactory	factory 	=	DocumentBuilderFactory.newInstance();
									factory.setValidating(false);
									factory.setCoalescing(true);
	final	DocumentBuilder			db			=	factory.newDocumentBuilder();
	final	HttpPost 				httpPost	=	new HttpPost(WBXCONorg.this.wapiURL);
									httpPost.setEntity(new UrlEncodedFormEntity(params, org.apache.http.Consts.UTF_8));
	final	CloseableHttpResponse	httpRes		=	HTTPSCLIENT.execute(httpPost,new BasicHttpContext());
						try{		dom			=	db.parse(httpRes.getEntity().getContent());
						}finally{	httpRes.close();
						}
					} catch (final SocketException se){
						if (retry<3){//Catches issues with WebEx Connect server connection
							System.err.println("SocketException making wapi call. Retry:"+(retry+1));
							return getDoc(retry+1);
						}else
							throw new WBXCONexception(se);
					} catch (final Exception e){
						throw new WBXCONexception(e);
					}
					if (dom!=null){
	final	NodeList				result		=	dom.getElementsByTagName("result");
						if (result==null || result.item(0)==null || !result.item(0).getTextContent().equalsIgnoreCase("success"))
							throw new WBXCONexception("executeQueued("+getParamsAsString(params)+").getDoc(): [RESULT]:"+result.item(0).getTextContent()+" [ERROR}:"+documentGetErrorString(dom));
					}
					return dom;
				}
			}).get();

		} catch (final Exception e){
			throw new WBXCONexception(e);
		}
	}

	private final static String getMethodName() {
		return getMethodName(2);
	}

	private final static String getMethodName(final int level) {
final	StackTraceElement element	=	Thread.currentThread().getStackTrace()[level];
		return element.getClassName()+"."+element.getMethodName();
	}

	private final static String getParamsAsString(final List<NameValuePair> params){
final	StringBuilder	p	=	new StringBuilder();
		for (final NameValuePair param:params)
			p.append("&"+param.getName()+"="+param.getValue());
			
		return p.toString();
	}

/*
 * Here is a list of parameters that are required/supported by each command [cmd=] in a REST call
 * ╔═══════════╦═══════╦════════╦════════╦═════╦══════╦═════╦═════╦════════╦══════════╦════════╦══════════╦════════╗
 * ║ Parameter ║ login ║ create ║ delete ║ get ║ enum ║ set ║ add ║ remove ║ validate ║ commit ║ rollback ║ logout ║
 * ╠═══════════╬═══════╬════════╬════════╬═════╬══════╬═════╬═════╬════════╬══════════╬════════╬══════════╬════════╣
 * ║ username  ║ X     ║        ║        ║     ║      ║     ║     ║        ║          ║        ║          ║        ║
 * ║ password  ║ X     ║        ║        ║     ║      ║     ║     ║        ║          ║        ║          ║        ║
 * ║ cred      ║       ║ X      ║ X      ║ X   ║ X    ║ X   ║ X   ║ X      ║ X        ║ X      ║ X        ║ X      ║
 * ║ type      ║       ║ X      ║ X      ║ X   ║ X    ║ X   ║ X   ║ X      ║ X        ║        ║          ║        ║
 * ║ use       ║       ║ X      ║        ║     ║      ║     ║     ║        ║          ║        ║          ║        ║
 * ║ id        ║       ║        ║ X      ║ X   ║      ║ X   ║ X   ║ X      ║ X        ║        ║          ║        ║
 * ║ where     ║       ║        ║ X      ║ X   ║      ║ X   ║ X   ║        ║          ║        ║          ║        ║
 * ║ order     ║       ║        ║        ║ X   ║      ║     ║     ║        ║          ║        ║          ║        ║
 * ║ page      ║       ║        ║        ║ X   ║      ║     ║     ║        ║          ║        ║          ║        ║
 * ║ pageSize  ║       ║        ║        ║ X   ║      ║     ║     ║        ║          ║        ║          ║        ║
 * ║ select    ║       ║        ║        ║ X   ║ X    ║     ║     ║ X      ║          ║        ║          ║        ║
 * ║ xml       ║       ║        ║        ║     ║      ║ X   ║ X   ║        ║          ║        ║          ║        ║
 * ║ tzid      ║       ║        ║        ║ X   ║      ║ X   ║ X   ║        ║          ║        ║          ║        ║
 * ║ locale    ║       ║        ║        ║     ║      ║     ║     ║        ║          ║        ║          ║        ║
 * ║ task      ║       ║        ║        ║     ║      ║     ║     ║        ║          ║        ║          ║        ║
 * ╚═══════════╩═══════╩════════╩════════╩═════╩══════╩═════╩═════╩════════╩══════════╩════════╩══════════╩════════╝
 */

	/**
	 * Query the list of configured CMCU cluster names
	 * 
	 * @return {@link java.util.Set} of CMCU cluster names
	 * @throws WBXCONexception
	 */
	final Set<String> restapiDomainGetCMCUClusterSet() throws WBXCONexception{
final	Set<String>			clusters			=	new TreeSet<String>();
final	List<NameValuePair>	params				=	new ArrayList<NameValuePair>();
							params.add(new BasicNameValuePair("cmd","get"));
							params.add(new BasicNameValuePair("type","ois"));
							params.add(new BasicNameValuePair("select","/orgIntgServ/ext/cupcIntegration/CUCMClusterName"));
							params.add(new BasicNameValuePair("where", "<and><eq><path>orgID</path><value>"+this.orgID+"</value></eq><eq><path>intgServTypeID</path><value>ICUPC</value></eq></and>"));
final	Document 			dom					=	executeQueued(params);
final	NodeList			clusternameselement	=	dom.getElementsByTagName("CUCMClusterName");
		for (int c=0;c<clusternameselement.getLength();c++)
			clusters.add(clusternameselement.item(c).getTextContent());
		return clusters;
	}
	
	/**
	 * @return @link java.util.Set} of WBX:privilege values
	 * @throws WBXCONexception
	 */
	final Set<String> restapiDomainGetPrivilegeSet() throws WBXCONexception{
final	Set<String>			privs				=	new TreeSet<String>();
final	List<NameValuePair>	params				=	new ArrayList<NameValuePair>();
							params.add(new BasicNameValuePair("cmd","execute"));
							params.add(new BasicNameValuePair("task","ProbeAccess"));
							params.add(new BasicNameValuePair("xml", "<tests><dump-privileges/></tests>"));
final	Document 			dom					=	executeQueued(params);
final	NodeList			privileges			=	dom.getElementsByTagName("privilege");
		for (int c=0;c<privileges.getLength();c++)
			privs.add(privileges.item(c).getTextContent());
		return privs;
	}

	/**
	 * @return WBX GroupID specified by the groupName
	 * @param groupName	the groupName for the WBX Group object
	 * @throws WBXCONexception
	 */
	final WBXCONuser.WBXCONGROUPID restapiGroupGetIDForName(final String groupName) throws WBXCONexception{
final	List<NameValuePair>	params		=	new ArrayList<NameValuePair>();
							params.add(new BasicNameValuePair("cmd","get"));
							params.add(new BasicNameValuePair("type","group"));
							params.add(new BasicNameValuePair("select","groupID"));
							params.add(new BasicNameValuePair("where","<eq><path>/groupName</path><value>"+groupName+"</value></eq>"));
final	Document 			dom			=	executeQueued(params);
		return new WBXCONuser.WBXCONGROUPID(dom.getElementsByTagName("groupdID").item(0).getTextContent());
	}
	
	/**
	 * @return WBX GroupID of newly created group
	 * @param groupName	the groupName for the WBX Group object
	 * @throws WBXCONexception
	 */
	final WBXCONuser.WBXCONGROUPID restapiGroupCreate(final String groupName) throws WBXCONexception{
final	List<NameValuePair>	params		=	new ArrayList<NameValuePair>();
							params.add(new BasicNameValuePair("cmd","create"));
							params.add(new BasicNameValuePair("type","group"));
							params.add(new BasicNameValuePair("xml","<group><groupName>"+groupName+"</groupName></group>"));
final	Document 			dom			=	executeQueued(params);
		return new WBXCONuser.WBXCONGROUPID(dom.getElementsByTagName("groupdID").item(0).getTextContent());
	}

	/**
	 * Creates a new WebExConnect User Account.<br />
	 * <br />
	 * cmd=execute<br />
	 * task=ProvisionUserComplete<br />
	 * 
	 * @param account			a <code>WBXCONuser</code> object that represents a new user
	 * @param password			a String password to use as the initial password for the new account
	 * @param sendWelcomeEmail	a true/false flag that determine if WebEx should send the normal Welcome Email to the new user
	 * @throws WBXCONexception
	 */
	final WBXCONuser.WBXCONUID restapiAccountCreate(final WBXCONuser account, final String pass, final boolean sendWelcomeEmail) throws WBXCONexception {
final	List<NameValuePair>	params;
		try{
final	Document			doc				=	account.marshallXML();
final	Element				orgID			=	doc.createElement("orgID");
							orgID.appendChild(doc.createTextNode(this.orgID));
final	Element				ISProviderID	=	doc.createElement("ISProviderID");
							ISProviderID.appendChild(doc.createTextNode("WBX"));
final	Element				ISProviders		=	doc.createElement("ISProviders");
							ISProviders.appendChild(ISProviderID);
final	Element				password		=	doc.createElement("password");
							password.appendChild(doc.createTextNode(pass));
final	Node				user			=	doc.getElementsByTagName("user").item(0);
							user.appendChild(orgID);
							user.appendChild(ISProviders);
							user.appendChild(password);
			if (!sendWelcomeEmail){
final	Element				isSendCPIPMail	=	doc.createElement("isSendCPIPMail");
							isSendCPIPMail.appendChild(doc.createTextNode("false"));
							user.appendChild(isSendCPIPMail);
			}				params			=	new ArrayList<NameValuePair>();
							params.add(new BasicNameValuePair("cmd","execute"));
							params.add(new BasicNameValuePair("task","ProvisionUserComplete"));
							params.add(new BasicNameValuePair("xml",documentToXMLstring(user)));
		} catch (final Exception e){
			throw new WBXCONexception(e);
		}
		return new WBXCONuser.WBXCONUID(documentGetTextContentByTagName(executeQueued(params),"userID"));
	}

	/**
	 * This call will <b>permanently delete</b> a user account.
	 * 
	 * @param id The userName or the {@link WBXCONuser.WBXCONUID} of the account
	 * @return {@link org.w3c.dom.Document} the xml returned from the call
	 * @throws WBXCONexception
	 */
	final Document restapiAccountDelete(final Object id) throws WBXCONexception, NullPointerException{
final	List<NameValuePair>	params		=	new ArrayList<NameValuePair>();
							params.add(new BasicNameValuePair("cmd","delete"));
							params.add(new BasicNameValuePair("type","user"));
		if (id instanceof WBXCONuser.WBXCONUID)
							params.add(new BasicNameValuePair("id",((WBXCONuser.WBXCONUID)id).toString()));
		else				params.add(new BasicNameValuePair("where", "<eq><path>/user/userName</path><value>"+
																		(!((String)id).contains("@"+this.orgName)
																				?id+"@"+this.orgName
																				:id)+
																		"</value></eq>"));
final	Document 			dom			=	executeQueued(params);//documentPrettyPrint(dom,System.out);
final	NodeList			count		=	dom.getElementsByTagName("count");
		if (Integer.parseInt(count.item(0).getTextContent())<1)
			throw new NullPointerException(getMethodName()+"(\""+id+"\"):[ERROR]:No WBX User record exists");
		return dom;
	}

	/**
	 * Use to pull the raw Document response from the REST APIs that represents a WBX User object.
	 * 
	 * @param id The userName or the {@link WBXCONuser.WBXCONUID} of the account
	 * @return {@link org.w3c.dom.Document} the xml returned from the call
	 * @throws WBXCONexception
	 */
	final Document restapiAccountGetAsDom(final Object id) throws WBXCONexception, NullPointerException{
final	List<NameValuePair>	params		=	new ArrayList<NameValuePair>();
							params.add(new BasicNameValuePair("cmd","get"));
							params.add(new BasicNameValuePair("type","user"));
							params.add(new BasicNameValuePair("select","*"));
		if (id instanceof WBXCONuser.WBXCONUID)
							params.add(new BasicNameValuePair("id",((WBXCONuser.WBXCONUID)id).toString()));
		else				params.add(new BasicNameValuePair("where", "<eq><path>/user/userName</path><value>"+
																		(!((String)id).contains("@"+this.orgName)
																				?id+"@"+this.orgName
																				:id)+
																		"</value></eq>"));
final	Document 			dom			=	executeQueued(params);//documentPrettyPrint(dom,System.out);
final	NodeList			count		=	dom.getElementsByTagName("count");
		if (Integer.parseInt(count.item(0).getTextContent())<1)
			throw new NullPointerException(getMethodName()+"(\""+id+"\"):[ERROR]:No WBX User record exists");
		return dom;
	}

	/**
	 * Returns a <code>WBXCONuser</code> object for the given id.<br />
	 * <br />
	 * Shortcut to call restapiAccountGetAsDom and unmarshall the returned Document into a WBXCONuser object
	 * 
	 * @param id This can either be the CECID <b>or</b> the WBX UserID for the account
	 * @return a {@link WBXCONuser} object containing a WebEx Account record.
	 * @throws WBXCONexception
	 * @trhows NullPointerException
	 */
	final WBXCONuser restapiAccountGet(final Object id) throws WBXCONexception, NullPointerException{
		return WBXCONuser.unmarshallXML(restapiAccountGetAsDom(id));
	}

	/**
	 * Modify a WBX User object
	 * 
	 * @param id The userName or the {@link WBXCONuser.WBXCONUID} of the account
	 * @param xml The changes to the User object
	 * @throws WBXCONexception
	 */
final void restapiAccountModify(final Object id, final String xml) throws WBXCONexception{
final	List<NameValuePair>	params		=	new ArrayList<NameValuePair>();
							params.add(new BasicNameValuePair("cmd","set"));
							params.add(new BasicNameValuePair("type","user"));
							params.add(new BasicNameValuePair("xml","<user>"+xml+"</user>"));
		if (id instanceof WBXCONuser.WBXCONUID)
							params.add(new BasicNameValuePair("id",((WBXCONuser.WBXCONUID)id).toString()));
		else				params.add(new BasicNameValuePair("where", "<eq><path>/user/userName</path><value>"+
																		(!((String)id).contains("@"+this.orgName)
																				?id+"@"+this.orgName
																				:id)+
																		"</value></eq>"));
							executeQueued(params);
	}

	/**
	 * Makes the REST API call to grant a Special Privilege
	 * @param id The userName or the {@link WBXCONuser.WBXCONUID} of the account to be granted the special privilege
	 * @param specialPrivilege The <code>WBX:</code> special privilege name
	 * @param op The operation: <code>add</code> or <code>remove</code>
	 * @throws WBXCONexception
	 */
	final void restapiAccountAssertSpecialPrivilege(final Object id,final String specialPrivilege,final String op) throws WBXCONexception{
		if (!op.equals("add") && !op.equals("remove"))
			throw new WBXCONexception("Not a valid op value.  Must be: add or remove");
final	WBXCONuser.WBXCONUID	realid;
		if (id instanceof String)
								realid	=	restapiAccountGet((id)).getWBXUID();
		else					realid	=	(WBXCONuser.WBXCONUID) id;
final	List<NameValuePair>		params		=	new ArrayList<NameValuePair>();
								params.add(new BasicNameValuePair("cmd","execute"));
								params.add(new BasicNameValuePair("task","AssertPrivilege"));
								params.add(new BasicNameValuePair("xml","<users><user><userID>"+realid+"</userID><privilegeID>"+specialPrivilege+"</privilegeID><op>"+op+"</op><orgID>"+this.orgID+"</orgID></user></users>"));
								executeQueued(params);
	}

	/**
	 * Removes WBX User object's profile information<br />
	 * <br />
	 * *Note: [cmd=remove] usually only works on /usr/ext//* xpaths, so this method is going to assume that as a starting place.
	 * 
	 * @param id 		The {@link WBXCONuser.WBXCONUID} for the user account
	 * @param xpath 	The child node of EXT to be removed
	 * @throws WBXCONexception
	 */
	final void restapiAccountRemoveEXTchildNode(final WBXCONuser.WBXCONUID id, String xpath) throws WBXCONexception{
		if (xpath.startsWith("/usr/ext/"))
							xpath		=	xpath.substring(9);
		if (xpath.startsWith("/"))
							xpath		=	xpath.substring(1);
final	List<NameValuePair>	params		=	new ArrayList<NameValuePair>();
							params.add(new BasicNameValuePair("cmd","remove"));
							params.add(new BasicNameValuePair("type","user"));
							params.add(new BasicNameValuePair("select","/user/ext/"+xpath));
							params.add(new BasicNameValuePair("id",id.toString()));
							executeQueued(params);
	}

	/**
	 * Add a user to a WBX Group specified by the groupID
	 * @param id		The {@link WBXCONuser.WBXUID} of the user account to add to the group
	 * @param groupid	The {@link WBXCONuser.WBXCONGROUPID} for the WBX Group object
	 * @throws WBXCONexception
	 */
	final void restapiAccountAddToGroup(final WBXCONuser.WBXCONUID id, final WBXCONuser.WBXCONGROUPID groupid) throws WBXCONexception{
final	List<NameValuePair> params			=	new ArrayList<NameValuePair>();
							params.add(new BasicNameValuePair("cmd","create"));
							params.add(new BasicNameValuePair("type","membership"));
							params.add(new BasicNameValuePair("xml","<membership><container><containerID>"+groupid+"</containerID><containerType>grp</containerType></container><member><userID>"+id+"</userID></member></membership>"));
							executeQueued(params);
	}

	/**
	 * Queries the set of WBX groups that the given user belongs to.
	 * @param id 	The {@link WBXCONuser.WBXUID} of the account or the userName
	 * @return 		Set of {@link String} values in the format: groupID|groupName
	 * @throws WBXCONexception
	 */
	final Set<String> restapiAccountGetGroups(final Object id) throws WBXCONexception{
		Document 				dom;
final	WBXCONuser.WBXCONUID	realid;
		if (id instanceof String)
								realid	=	restapiAccountGet((id)).getWBXUID();
		else					realid	=	(WBXCONuser.WBXCONUID) id;
final	Set<String>				groups		=	new HashSet<String>();
final	List<NameValuePair>		params		=	new ArrayList<NameValuePair>();
								params.add(new BasicNameValuePair("cmd","get"));
								params.add(new BasicNameValuePair("type","membership"));
								params.add(new BasicNameValuePair("select","container"));
								params.add(new BasicNameValuePair("where","<eq><path>membership/member/userID</path><value>"+realid+"</value></eq>"));
								dom			=	executeQueued(params);
		if (dom.getElementsByTagName("containerID").getLength()>0){
			for (int c=0;c<dom.getElementsByTagName("containerID").getLength();c++){
final	String					groupID		=	dom.getElementsByTagName("containerID").item(c).getTextContent();
								params.clear();
								params.add(new BasicNameValuePair("cmd","get"));
								params.add(new BasicNameValuePair("type","group"));
								params.add(new BasicNameValuePair("select","groupName"));
								params.add(new BasicNameValuePair("where","<eq><path>/groupID</path><value>"+groupID+"</value></eq>"));
								dom			=	executeQueued(params);
								groups.add(groupID+"|"+documentGetTextContentByTagName(dom,"groupName"));
			}
		}
		return groups;
	}

	/**
	 * Executes a <code>cmd=get&type=user</code> query.<br />
	 * <br /><b>
	 * This is depreciated because these queries are <i>blocking</i>,
	 * consuming a listener thread for the duration of the query.
	 * </b>
	 * @param select
	 * @param where
	 * @param order
	 * @param base
	 * @return {@link org.w3c.dom.Document} the xml returned from the call
	 * @throws WBXCONexception
	 */
	@Deprecated
	final Document restapiAccountQuery(final String select, final String where, final String order, final String base) throws WBXCONexception{
final	List<NameValuePair>	params		=	new ArrayList<NameValuePair>();
							params.add(new BasicNameValuePair("cmd","get"));
							params.add(new BasicNameValuePair("type","user"));
							params.add(new BasicNameValuePair("select",select));
							params.add(new BasicNameValuePair("where", where));
		if (order!=null)	params.add(new BasicNameValuePair("order", order));
		if (base!=null)		params.add(new BasicNameValuePair("base", base));
		return executeQueued(params);
	}

	final String getWAPIURL() throws WBXCONexception{
final	String	cred	=	getDomainCredToken();
		return this.wapiURL+"?cred="+cred;
	}

	/**
	 * Parses out any WebEx Connect error message(s) from the REST API call results
	 * 
	 * @param dom	{@link org.w3c.dom.Document} to parse for any WebEx Connect "wapi" errors
	 * @return any error message found
	 */
	final static String documentGetErrorString(final Document dom){
final	StringBuilder	error		=	new StringBuilder();
		try{
		NodeList		nodes		=	dom.getElementsByTagName("message");
			if (nodes.getLength()==0)
						nodes		=	dom.getElementsByTagName("reason");
final	String			message		=	nodes.item(0).getTextContent();
			if (message.contains("java.lang.NullPointerException"))
						error.append(dom.getElementsByTagName("exceptionID").item(0).getTextContent()+"\n");
			else
				for (int m=0;m<nodes.getLength();m++)
						error.append(nodes.item(m).getTextContent()+"\n");
		} catch (final Exception e){
						error.append(getMethodName()+"(Document)::No valid error message was returned from WebEx. [ERROR]:"+e+" [MSG]:"+e.getMessage());
		}
		return error.toString();
	}

	/**
	 * This is a helper method that correctly handles setting the text content
	 * for a given tag in a DOM <code>Document</code>
	 * 
	 * @param tagName	DOM tag to create and add to the parent node.
	 * @param value		The text value to be assigned to the tag
	 * @param parent	The parent Node that the tag/text will be added to
	 */
	final static void documentSetTextContentOfNode(final String tagName, final String value, final Node parent) {
final	Document	dom		=	parent.getOwnerDocument();
final	Node		node	=	dom.createElement(tagName);
final	Text		nodeVal	=	dom.createTextNode(value);
					node.appendChild(nodeVal);
					parent.appendChild(node);
	}

	/**
	 * This is a helper method that returns the text content from the first node with
	 * the specified tag name
	 * @param {@link org.w3c.dom.Document} to parse
	 * @param tagname
	 * @return text content for the specified tag or null if no text content
	 */
	final static String documentGetTextContentByTagName(final Document dom, final String tagname){
		try{
			return dom.getElementsByTagName(tagname).item(0).getTextContent();
		} catch (final NullPointerException npe){
			return null;
		}
	}

	/**
	 * This is a helper method that returns a String form of a DOM {@link org.w3c.dom.Node} Object
	 * starting at the parent/child level specified by root node
	 * @param rootnode	the DOM {@link org.w3c.dom.Node} to use as the root
	 * @return an XML form of <code>rootnode</code> and any children
	 * @throws IOException
	 * @throws TransformerException
	 */
	final static String documentToXMLstring(final Node rootnode) throws TransformerException{
final	StringWriter 		out			=	new StringWriter();
final	TransformerFactory	tf		 	=	TransformerFactory.newInstance();
final	Transformer 		transformer	=	tf.newTransformer();
							transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
							transformer.setOutputProperty(OutputKeys.METHOD, "xml");
							transformer.setOutputProperty(OutputKeys.INDENT, "no");
							transformer.setOutputProperty(OutputKeys.ENCODING, org.apache.http.Consts.UTF_8.displayName());
							transformer.transform(new DOMSource(rootnode), new StreamResult(out));
		return out.toString();
	}

	/**
	 * This is a helper method that returns a String form of a {@link org.w3c.dom.Document}
	 * object, fully indented into a "pretty print" format.
	 * @param doc	{@link org.w3c.dom.Document} to be parsed
	 * @param out	OutputStream to copy the text into
	 * @throws IOException
	 * @throws TransformerException
	 */
	final static void documentPrettyPrint(final Document doc, final OutputStream out) throws IOException, TransformerException {
final	Transformer	transformer	=	TransformerFactory.newInstance().newTransformer();
					transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
					transformer.setOutputProperty(OutputKeys.METHOD, "xml");
					transformer.setOutputProperty(OutputKeys.INDENT, "yes");
					transformer.setOutputProperty(OutputKeys.ENCODING, org.apache.http.Consts.UTF_8.displayName());
					transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
					transformer.transform(new DOMSource(doc), new StreamResult(out));
	}
	/*
	 * Trims any whitespace from beginning or end, as well as removes any quotes from begining or end
	 * @param stringToTrim
	 * @return
	 */
	final static String trim(String stringToTrim){
		if (stringToTrim==null)
			return null;
		try{
			stringToTrim		=	stringToTrim.trim();
			if (stringToTrim.startsWith("\"") && stringToTrim.endsWith("\"")){
				stringToTrim	=	stringToTrim.substring(1,stringToTrim.length()-1);
			}
			if (stringToTrim.startsWith("'") && stringToTrim.endsWith("'")){
				stringToTrim	=	stringToTrim.substring(1,stringToTrim.length()-1);
			}
		} catch (final Exception e){}
		return stringToTrim;
	}

	/**
	 * This class is used to store the Password Rules for the org
	 *
	 */
	final class PWSRule{
		
		private final	int	minLength;
		private final	int	maxLength;
		private final	int minLCaseCount;
		private final	int minUCaseCount;
		private final	int minNumCount;
		private final	int minSpecialCount;
		
		/**
		 * This class is used to store the Password Rules for the org
		 * 
		 * @param minLength			Minimum required character length for the password
		 * @param minimumAlpha		Minimum number of alphabetic characters required in the password
		 * @param minNumeric		Minimum number of numeric characters required in the password
		 * @param minSpecial		Minimum number of special characters required in the password
		 * @param requireMixedCase	Require a mixture of upper and lower case alphabetic characters
		 */
		PWSRule(final int minLength, final int minimumAlpha, final int minNumeric, final int minSpecial, final boolean requireMixedCase){
			this.minLength			=	minLength;
			this.maxLength			=	15;
			this.minLCaseCount		=	minimumAlpha;
			//typical WebEx double speak, if requiredMixedCase it means to require at least one upper case alphabetic character
			this.minUCaseCount		=	requireMixedCase?1:0;
			this.minNumCount		=	minNumeric;
			this.minSpecialCount	=	minSpecial;
		}
		
		/**
		 * @return	a random password that matches the rules for the org
		 */
		String getRandomPassword(){
			return PasswordUtils.generateRandom(this.minLength, this.maxLength, this.minLCaseCount, this.minUCaseCount, this.minNumCount, this.minSpecialCount);
		}
	}

	/**
	 * Running a WebEx report requires that the account used to initialize WBXCONorg
	 * have the special privilege: WBX:RunOrgReport
	 *
	 */
	public final class REPORTJOB{
		private final	transient	String	jobID;
		
		/**
		 * Examples:<br />
		 * 	"org_connect_user_realtime_report","&lt;bucketSize&gt;total&lt;/bucketSize&gt;"<br />
		 * 	"org_connect_user_report","&lt;time&gt;&lt;startTime&gt;"+startTime+"&lt;/startTime&gt;&lt;endTime&gt;"+endTime+"&lt;/endTime&gt;&lt;/time&gt;&lt;bucketSize&gt;month&lt;/bucketSize&gt;"
		 * 
		 * @param reporttype		the various WebEx Connect report types
		 * @param reportparamsxml	xml that would normally fall between the &lt;params&gt; tag.
		 * @throws WBXCONexception
		 */
		public REPORTJOB(final String reporttype, final String reportparamsxml) throws WBXCONexception{
			this(reporttype,reportparamsxml,true);
		}
		
		/**
		 * Examples:<br />
		 * 	"org_connect_user_realtime_report","&lt;bucketSize&gt;total&lt;/bucketSize&gt;"<br />
		 * 	"org_connect_user_report","&lt;time&gt;&lt;startTime&gt;"+startTime+"&lt;/startTime&gt;&lt;endTime&gt;"+endTime+"&lt;/endTime&gt;&lt;/time&gt;&lt;bucketSize&gt;month&lt;/bucketSize&gt;"
		 * 
		 * @param reporttype		The various WebEx Connect report types
		 * @param reportparamsxml	xml that would normally fall between the &lt;params&gt; tag.
		 * @param forcenew			Forces a new report to be generated, otherwise if false the last
		 * 							report with matching <code>reporttype</code> submitted in the same
		 * 							calendar day is retrieved instead.
		 * @throws WBXCONexception
		 */
		public REPORTJOB(final String reporttype, final String reportparamsxml, final boolean forcenew) throws WBXCONexception{
final	String				jobid		=	forcenew?null:restapiGetJobID(reporttype);
			if (jobid==null){
final	List<NameValuePair> params		=	new ArrayList<NameValuePair>();
							params.add(new BasicNameValuePair("cmd","execute"));
							params.add(new BasicNameValuePair("task","Report"));
							params.add(new BasicNameValuePair("xml","<report><name>"+reporttype+"</name><type>"+reporttype+"</type><params>"+reportparamsxml+"<noSendingEmail>true</noSendingEmail></params></report>"));
				try{		executeQueued(params);
				} catch (final WBXCONexception e){
					if (!e.getMessage().contains("wapi.concurrent_job_error"))
						throw e;
				}
				this.jobID	=	restapiGetJobID(reporttype);
			} else {
				this.jobID	=	jobid;
			}
		}
		
		/**
		 * Examples:<br />
		 * 	"org_connect_user_realtime_report","&lt;bucketSize&gt;total&lt;/bucketSize&gt;"<br />
		 * 	"org_connect_user_report","&lt;time&gt;&lt;startTime&gt;"+startTime+"&lt;/startTime&gt;&lt;endTime&gt;"+endTime+"&lt;/endTime&gt;&lt;/time&gt;&lt;bucketSize&gt;month&lt;/bucketSize&gt;"
		 * 
		 * @param reporttype		The various WebEx Connect report types
		 * @param reportparamsxml	xml that would normally fall between the &lt;params&gt; tag.
		 * @param forcenew			If false the last report with matching <code>reporttype</code> submitted
		 * 							in the same calendar day is retrieved instead of generating a new report.
		 * @throws WBXCONexception
		 */
		public REPORTJOB(final String reporttype, final String reportparamsxml, final boolean forcenew, final File outputfile) throws WBXCONexception{
final	String				jobid		=	forcenew?null:restapiGetJobID(reporttype);
			if (jobid==null){
final	List<NameValuePair> params		=	new ArrayList<NameValuePair>();
							params.add(new BasicNameValuePair("cmd","execute"));
							params.add(new BasicNameValuePair("task","Report"));
							params.add(new BasicNameValuePair("xml","<report><name>"+reporttype+"</name><type>"+reporttype+"</type><params>"+reportparamsxml+"<noSendingEmail>true</noSendingEmail></params></report>"));
				try{		executeQueued(params);
				} catch (final WBXCONexception e){
					if (!e.getMessage().contains("wapi.concurrent_job_error"))
						throw e;
				}
				this.jobID	=	restapiGetJobID(reporttype);
			} else {
				this.jobID	=	jobid;
			}
		}
		
		/**
		 * Retrieves the report's binary data and copies it to the output stream.<br /><br />
		 * <b>
		 * This job <i>blocks</i> a REST API thread for large data transfers,
		 * so the actual RESTAPI call method is synchronized to prevent DoS
		 * of the production listener threads.
		 * </b>
		 * @param out	The OutputStream to copy the data to
		 * @throws InterruptedException
		 * @throws WBXCONexception
		 */
		public void getReport(final OutputStream out) throws InterruptedException, WBXCONexception{
			while(!restapiIsJobComplete())
				Thread.sleep(60*1000);
			restapiOutputReport(out);
		}
		
		/**
		 * Populates two sets representing all accounts for the org
		 * 
		 * This class is only called by the matching _WBXCONfactory class
		 * 
		 * @param activated		{@link java.util.SortedSet} that will be populated with userNames of all currently <b>activated</b> accounts
		 * @param deactivated	{@link java.util.SortedSet} that will be populated with userNames of all currently <b>deactivated</b> accounts
		 * @throws WBXCONexception
		 */
		void populateActivatedDeactivedSets(final SortedSet<String> activated, final SortedSet<String> deactivated) throws WBXCONexception, InterruptedException{
			while(!restapiIsJobComplete())
				Thread.sleep(60*1000);//Sleep for one minute before trying again
			restapiPopulateActivatedDeactivedSets(activated,deactivated);
		}
		
		private final String restapiGetJobID(final String reporttype) throws WBXCONexception{
			try{
final	List<NameValuePair>	params 		=	new ArrayList<NameValuePair>();
							params.add(new BasicNameValuePair("cmd","get"));
							params.add(new BasicNameValuePair("type","thing"));
							params.add(new BasicNameValuePair("select","thingID:ext/WBX/jobID:ext/WBX/jobType:ext/WBX/jobName:ext/WBX/jobSubmissionTime_D:ext/WBX/status"));
							params.add(new BasicNameValuePair("where","<and>"+
																		"<and>"+
																			"<and>"+
																				"<and>"+
																					"<eq><path>/thing/ext/WBX/jobName</path><value>"+reporttype+"</value></eq>"+
																				"</and>"+
																				"<eq><path>/thing/ext/WBX/jobType</path><value>report</value></eq>"+
																			"</and>"+
																			"<eq><path>/thing/ext/WBX/RunAsuserId</path><value>"+WBXCONorg.this.wapiUser.getWBXUID()+"</value></eq>"+
																		"</and>"+
																		"<ge><path>/thing/ext/WBX/params/jobRunDate</path><value>"+new SimpleDateFormat("yyyy-MM-dd").format(new Date())+"</value></ge>"+
																	  "</and>"
															));
							params.add(new BasicNameValuePair("order","/thing/ext/WBX/jobSubmissionTime_D,DESC"));
final	Document			dom			=	executeQueued(params);//documentPrettyPrint(dom,System.out);params.clear();
				return dom.getElementsByTagName("jobID").item(0).getTextContent();
			} catch (final Exception e){
			}
			return null;
		}
		
		private	final boolean restapiIsJobComplete() throws WBXCONexception{
final	List<NameValuePair>	params 		=	new ArrayList<NameValuePair>();
							params.add(new BasicNameValuePair("cmd","get"));
							params.add(new BasicNameValuePair("type","thing"));
							params.add(new BasicNameValuePair("select","thingID:ext/WBX/status"));
							params.add(new BasicNameValuePair("id",this.jobID));
final	Document 			dom			=	executeQueued(params);//documentPrettyPrint(dom,System.out);
			if (dom.getElementsByTagName("status").item(0).getTextContent().equalsIgnoreCase("completed"))
				return true;
			return false;
		}
		
		private final synchronized InputStream restapiOutputReport(final OutputStream out) throws WBXCONexception{
		final InputStream 			ins			=	null;
			try{
final	List<NameValuePair>		params 		=	new ArrayList<NameValuePair>();
								params.add(new BasicNameValuePair("cmd","execute"));
								params.add(new BasicNameValuePair("task","Job"));
								params.add(new BasicNameValuePair("xml","<job><jobID>"+this.jobID+"</jobID><jobType>Report</jobType><action>getfile</action><fileType>outputFile</fileType></job>"));
								params.add(new BasicNameValuePair("cred",getDomainCredToken()));
final	HttpPost 				httpPost	=	new HttpPost(WBXCONorg.this.wapiREPORTURL);
								httpPost.setEntity(new UrlEncodedFormEntity(params, org.apache.http.Consts.UTF_8));
final	CloseableHttpResponse 	httpRes 	=	HTTPSCLIENT.execute(httpPost);
				try{			IOUtils.copy(httpRes.getEntity().getContent(), out);
				} finally {		httpRes.close();
				}
			} catch (final Exception e){
				throw new WBXCONexception(e);
			}
			return ins;
		}
		
		private final synchronized void restapiPopulateActivatedDeactivedSets(final SortedSet<String> activated, final SortedSet<String> deactivated) throws WBXCONexception{
			try{
final	List<NameValuePair>		params 		=	new ArrayList<NameValuePair>();
								params.add(new BasicNameValuePair("cmd","execute"));
								params.add(new BasicNameValuePair("task","Job"));
								params.add(new BasicNameValuePair("xml","<job><jobID>"+this.jobID+"</jobID><jobType>Report</jobType><action>getfile</action><fileType>outputFile</fileType></job>"));
								params.add(new BasicNameValuePair("cred",getDomainCredToken()));
final	HttpPost 				httpPost	=	new HttpPost(WBXCONorg.this.wapiREPORTURL);
								httpPost.setEntity(new UrlEncodedFormEntity(params, org.apache.http.Consts.UTF_8));
final	CloseableHttpResponse 	httpRes 	=	HTTPSCLIENT.execute(httpPost);
				try{
final	BufferedReader 			in 			=	new BufferedReader(new InputStreamReader(httpRes.getEntity().getContent()));
		String					line		=	null;
					while((line = in.readLine()) != null) {
final	String[]				parms		=	line.split(",");
						if (trim(parms[1]).equalsIgnoreCase("Activated")){
final	String					id			=	trim(parms[0]).toLowerCase();
								activated.add(id.substring(0,id.indexOf("@")));
						}else if (trim(parms[1]).equalsIgnoreCase("Deactivated")){
final	String					id			=	trim(parms[0]).toLowerCase();
								deactivated.add(id.substring(0,id.indexOf("@")));
						}
					}
				} finally {		httpRes.close(); }
			} catch (final Exception e){
				throw new WBXCONexception(e);
			}
		}
	}
	
final private static class MyThreadFactory implements ThreadFactory {

final	String		name;
final	ThreadGroup	group;

		MyThreadFactory(final String name){
			this.name	=	name;
			this.group	=	new ThreadGroup(name);
		}
		
		@Override
		public Thread newThread(final Runnable r) {
final Thread	t = new Thread(this.group,r);
				t.setName(this.name + t.getName());
//				t.setDaemon(true);
			return t;
		}
	}
}
