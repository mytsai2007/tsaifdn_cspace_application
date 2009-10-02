package org.collectionspace.chain.csp.persistence.services;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.collectionspace.chain.util.jtmpl.InvalidJTmplException;
import org.collectionspace.chain.util.jxj.InvalidJXJException;
import org.collectionspace.chain.util.jxj.JXJFile;
import org.collectionspace.chain.util.jxj.JXJTransformer;
import org.collectionspace.chain.util.xtmpl.InvalidXTmplException;
import org.collectionspace.csp.api.persistence.ExistException;
import org.collectionspace.csp.api.persistence.Storage;
import org.collectionspace.csp.api.persistence.UnderlyingStorageException;
import org.collectionspace.csp.api.persistence.UnimplementedException;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Node;
import org.dom4j.io.SAXReader;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class ServicesIntakeStorage implements Storage {
	private ServicesConnection conn;
	private JXJTransformer jxj;
	private ServicesIdentifierMap cspace_264_hack;
	
	// XXX refactor into util
	private Document getDocument(String in) throws DocumentException, IOException {
		String path=getClass().getPackage().getName().replaceAll("\\.","/");
		InputStream stream=Thread.currentThread().getContextClassLoader().getResourceAsStream(path+"/"+in);
		SAXReader reader=new SAXReader();
		Document doc=reader.read(stream);
		stream.close();
		return doc;
	}
	
	public ServicesIntakeStorage(ServicesConnection conn) throws InvalidJXJException, DocumentException, IOException {
		this.conn=conn;
		JXJFile jxj_file=JXJFile.compile(getDocument("intake.jxj"));
		jxj=jxj_file.getTransformer("intake");
		cspace_264_hack=new ServicesIdentifierMap(conn,
				"intakes",
				"intake-list/intake-list-item",
				"intake/packingNote");
	}

	public String autocreateJSON(String filePath, JSONObject jsonObject)
			throws ExistException, UnimplementedException, UnderlyingStorageException {
		try {
			Document doc=jxj.json2xml(jsonObject);
			System.err.println(doc.asXML());
			ReturnedURL url = conn.getURL(RequestMethod.POST,"intakes/",doc);
			if(url.getStatus()>299 || url.getStatus()<200)
				throw new UnderlyingStorageException("Bad response "+url.getStatus());
			return url.getURLTail();
		} catch (ConnectionException e) {
			throw new UnderlyingStorageException("Service layer exception",e);
		} catch (InvalidXTmplException e) {
			throw new UnimplementedException("Error in template",e);
		}
	}

	private JSONObject cspace264Hack_munge(JSONObject in,String path) throws UnderlyingStorageException {
		try {
		JSONObject out=new JSONObject();
		Iterator t=in.keys();
		while(t.hasNext()) {
			String k=(String)t.next();
			out.put(k,in.get(k));
		}
		out.put("idTunnel",path);
		return out;
		} catch(JSONException e) {
			throw new UnderlyingStorageException("Could not bodge intake id");
		}
	}
	
	public void createJSON(String filePath, JSONObject jsonObject)
			throws ExistException, UnimplementedException, UnderlyingStorageException {
		jsonObject=cspace264Hack_munge(jsonObject,filePath);
		autocreateJSON("",jsonObject);
	}
	
	private boolean cspace268Hack_empty(Document doc) {
		return doc.selectNodes("intake/*").size()==0;
	}
	
	public void deleteJSON(String filePath) throws ExistException,
			UnimplementedException, UnderlyingStorageException {
		try {
			ReturnedDocument doc = conn.getXMLDocument(RequestMethod.GET,"intakes/"+filePath);
			String csid=null;
			if((doc.getStatus()>199 && doc.getStatus()<300) && !cspace268Hack_empty(doc.getDocument())) {
				csid=filePath;
			} else {
				cspace_264_hack.blastCache();
				csid=cspace_264_hack.getCSID(filePath);
			}
			int status=conn.getNone(RequestMethod.DELETE,"intakes/"+csid,null);
			if(status>299 || status<200) // XXX CSPACE-73, should be 404
				throw new UnderlyingStorageException("Service layer exception status="+status);
		} catch (ConnectionException e) {
			throw new UnderlyingStorageException("Service layer exception",e);
		}		
	}

	@SuppressWarnings("unchecked")
	public String[] getPaths(String rootPath) throws ExistException, UnimplementedException, UnderlyingStorageException {
		try {
			List<String> out=new ArrayList<String>();
			ReturnedDocument all = conn.getXMLDocument(RequestMethod.GET,"intakes/");
			if(all.getStatus()!=200)
				throw new ConnectionException("Bad request during identifier cache map update: status not 200");
			List<Node> objects=all.getDocument().selectNodes("intake-list/intake-list-item");
			for(Node object : objects) {
				String csid=object.selectSingleNode("csid").getText();
				int idx=csid.lastIndexOf("/");
				if(idx!=-1)
					csid=csid.substring(idx+1);
				String mid=cspace_264_hack.fromCSID(csid);
				out.add(mid);
			}
			return out.toArray(new String[0]);
		} catch (ConnectionException e) {
			throw new UnderlyingStorageException("Service layer exception",e);
		}
	}

	public JSONObject retrieveJSON(String filePath) throws ExistException,
			UnimplementedException, UnderlyingStorageException {
		try {
			// XXX Here's what we do because of CSPACE-264
			// 1. Check this isn't a genuine CSID (via autocreate): rely on guids not clashing with museum IDs
			ReturnedDocument doc = conn.getXMLDocument(RequestMethod.GET,"intakes/"+filePath);
			if((doc.getStatus()>199 && doc.getStatus()<300) && !cspace268Hack_empty(doc.getDocument())) {
				return jxj.xml2json(doc.getDocument());
			}
			boolean blasted=false;
			boolean exhausted=false;
			while(!exhausted) {
				// 2. Assume museum ID
				String csid=cspace_264_hack.getCSID(filePath);
				if(csid==null) {
					exhausted=true;
					break;
				}
				doc = conn.getXMLDocument(RequestMethod.GET,"intakes/"+csid);
				if(doc.getStatus()==404 || cspace268Hack_empty(doc.getDocument())) {
					if(!blasted) {
						cspace_264_hack.blastCache();
						exhausted=false;
						blasted=true;
					} else
						exhausted=true;
				} else
					break;
			}
			if(exhausted)
				throw new ExistException("Does not exist "+filePath);
			if(doc.getStatus()>299 || doc.getStatus()<200)
				throw new UnderlyingStorageException("Bad response "+doc.getStatus());
			return jxj.xml2json(doc.getDocument());
		} catch (ConnectionException e) {
			throw new UnderlyingStorageException("Service layer exception",e);
		} catch (InvalidJTmplException e) {
			throw new UnderlyingStorageException("Service layer exception",e);
		} catch (InvalidJXJException e) {
			throw new UnderlyingStorageException("Service layer exception",e);
		}
	}

	public void updateJSON(String filePath, JSONObject jsonObject)
			throws ExistException, UnimplementedException, UnderlyingStorageException {
		try {
			Document data=jxj.json2xml(jsonObject);
			ReturnedDocument doc = conn.getXMLDocument(RequestMethod.GET,"intakes/"+filePath);
			String csid=null;
			if((doc.getStatus()>199 && doc.getStatus()<300) && !cspace268Hack_empty(doc.getDocument())) {
				csid=filePath;
			} else {
				csid=cspace_264_hack.getCSID(filePath);
			}
			doc = conn.getXMLDocument(RequestMethod.PUT,"intakes/"+csid,data);
			if(doc.getStatus()==404 || cspace268Hack_empty(doc.getDocument()))
				throw new ExistException("Not found: intakes/"+csid);
			if(doc.getStatus()>299 || doc.getStatus()<200)
				throw new UnderlyingStorageException("Bad response "+doc.getStatus());
		} catch (ConnectionException e) {
			throw new UnderlyingStorageException("Service layer exception",e);
		} catch (InvalidXTmplException e) {
			throw new UnderlyingStorageException("Service layer exception",e);
		}

	}
}
