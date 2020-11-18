package uk.org.llgc.annotation.store.servlets;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;

import com.github.jsonldjava.utils.JsonUtils;

import java.io.IOException;

import uk.org.llgc.annotation.store.StoreConfig;
import uk.org.llgc.annotation.store.adapters.StoreAdapter;
import uk.org.llgc.annotation.store.contollers.AuthorisationController;
import uk.org.llgc.annotation.store.contollers.UserService;
import uk.org.llgc.annotation.store.data.Collection;
import uk.org.llgc.annotation.store.data.Manifest;
import uk.org.llgc.annotation.store.data.users.User;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;

import java.util.Date;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class CollectionServlet extends HttpServlet {
	protected static Logger _logger = LogManager.getLogger(CollectionServlet.class.getName()); 
	protected StoreAdapter _store = null;

	public void init(final ServletConfig pConfig) throws ServletException {
		super.init(pConfig);
		_store = StoreConfig.getConfig().getStore();
	}

   	public void doGet(final HttpServletRequest pReq, final HttpServletResponse pRes) throws IOException {
        User tUser = new UserService(pReq.getSession()).getUser();
        if (pReq.getRequestURI().endsWith("collection/all.json")) {

            // get list of Collections
            List<Collection> tCollections = _store.getCollections(tUser);
            // if empty create the default collection
            if (tCollections.isEmpty()) {
                Collection tDefaultCollection = new Collection();
                tDefaultCollection.setUser(tUser);
                tDefaultCollection.setLabel("Inbox");
                tDefaultCollection.createId(StoreConfig.getConfig().getBaseURI(pReq) + "/collection/");
                tDefaultCollection = _store.createCollection(tDefaultCollection);
                tCollections.add(tDefaultCollection);
            }
                
            Map<String,Object> tCollection = new HashMap<String,Object>();

            tCollection.put("@context", "http://iiif.io/api/presentation/2/context.json");
            tCollection.put("@id", StoreConfig.getConfig().getBaseURI(pReq) + "/collection/all.json");
            tCollection.put("@type", "sc:Collection");
            tCollection.put("label","Collection of all collections for this user");

            List<Map<String,Object>> tMembers = new ArrayList<Map<String,Object>>();
            for (Collection tCollectionObj : tCollections) {
                tMembers.add(tCollectionObj.toJson());
            }

            //tCollection.put("members", tMembers);
            tCollection.put("collections", tMembers);

            pRes.setStatus(HttpServletResponse.SC_OK);
            pRes.setContentType("application/ld+json; charset=UTF-8");
            pRes.setCharacterEncoding("UTF-8");
            JsonUtils.write(pRes.getWriter(), tCollection);
        } else {
            String relativeId = pReq.getRequestURI().substring(pReq.getRequestURI().lastIndexOf("/collection/"));
            String tCollectionId = StoreConfig.getConfig().getBaseURI(pReq) + relativeId;
            System.out.println("Looking for collection: " + tCollectionId);

            Collection tCollection = _store.getCollection(tCollectionId);
            pRes.setStatus(HttpServletResponse.SC_OK);
            pRes.setContentType("application/ld+json; charset=UTF-8");
            pRes.setCharacterEncoding("UTF-8");
            JsonUtils.write(pRes.getWriter(), tCollection.toJson());
        }
    }

    public void doDelete(final HttpServletRequest pReq, final HttpServletResponse pRes) throws IOException {
        User tUser = new UserService(pReq.getSession()).getUser();
       
        String relativeId = pReq.getRequestURI().substring(pReq.getRequestURI().lastIndexOf("/collection/"));
        String tCollectionId = StoreConfig.getConfig().getBaseURI(pReq) + relativeId;
        Collection tExistingCollection = _store.getCollection(tCollectionId);
        if (tExistingCollection == null) {
            Map<String,Object> tResponse = new HashMap<String,Object>();
            tResponse.put("code", pRes.SC_NOT_FOUND);
            tResponse.put("message", "Collection with URI " + tCollectionId + " not found");
            this.sendJson(pRes, pRes.SC_NOT_FOUND, tResponse);
        } else {
            AuthorisationController tAuth = new AuthorisationController(pReq.getSession());
            if (tAuth.allowDeleteCollection(tExistingCollection)) {
                _store.deleteCollection(tExistingCollection);
                pRes.setStatus(HttpServletResponse.SC_OK);
                pRes.setContentType("application/ld+json; charset=UTF-8");
                pRes.setCharacterEncoding("UTF-8");
                JsonUtils.write(pRes.getWriter(), tExistingCollection.toJson());
            } else {
                Map<String,Object> tResponse = new HashMap<String,Object>();
                tResponse.put("code", pRes.SC_UNAUTHORIZED);
                tResponse.put("message", "You can only edit your own collections unless you are Admin");
                this.sendJson(pRes, pRes.SC_UNAUTHORIZED, tResponse);
            }
        }
    }


	public void doPost(final HttpServletRequest pReq, final HttpServletResponse pRes) throws IOException {
        // create collection usually empty just with id and label
        User tUser = new UserService(pReq.getSession()).getUser();

        Collection tCollection = new Collection();
        tCollection.setUser(tUser);
        tCollection.setLabel(pReq.getParameter("name"));
        tCollection.createId(StoreConfig.getConfig().getBaseURI(pReq) + "/collection/");
        tCollection = _store.createCollection(tCollection);

        pRes.setStatus(HttpServletResponse.SC_OK);
        pRes.setContentType("application/ld+json; charset=UTF-8");
        pRes.setCharacterEncoding("UTF-8");
        JsonUtils.write(pRes.getWriter(), tCollection.toJson());
    }


	public void doPut(final HttpServletRequest pReq, final HttpServletResponse pRes) throws IOException {
        // Update collection typically moving manifests
        User tUser = new UserService(pReq.getSession()).getUser();
        Collection tFrom = _store.getCollection(pReq.getParameter("from"));
        if (pReq.getParameter("to") != null) {
            // this is a move request
            Collection tTo = _store.getCollection(pReq.getParameter("to"));

            AuthorisationController tAuth = new AuthorisationController(pReq.getSession());
            if (tAuth.allowCollectionEdit(tFrom) && tAuth.allowCollectionEdit(tTo)) {
                Manifest tManifest = new Manifest();
                tManifest.setURI(pReq.getParameter("manifest"));
                Manifest tFullManifest = _store.getManifest(tManifest.getURI());
                if (tFullManifest != null) {
                    tManifest = tFullManifest;
                }

                tFrom.remove(tManifest);
                _store.updateCollection(tFrom);

                tTo.add(tManifest);
                _store.updateCollection(tTo);

                Map<String,Object> tResponse = new HashMap<String,Object>();
                tResponse.put("code", pRes.SC_OK);
                tResponse.put("message", "Succesfully moved manifest to new collection");
                this.sendJson(pRes, pRes.SC_OK, tResponse);
            } else {
                Map<String,Object> tResponse = new HashMap<String,Object>();
                tResponse.put("code", pRes.SC_UNAUTHORIZED);
                tResponse.put("message", "You can only edit your own collections unless you are Admin");
                this.sendJson(pRes, pRes.SC_UNAUTHORIZED, tResponse);
            }
        } else {
            // This is a remove manifest from collection request
            AuthorisationController tAuth = new AuthorisationController(pReq.getSession());
            if (tAuth.allowCollectionEdit(tFrom)) {
                Manifest tManifest = new Manifest();
                tManifest.setURI(pReq.getParameter("manifest"));
                Manifest tFullManifest = _store.getManifest(tManifest.getURI());
                if (tFullManifest != null) {
                    tManifest = tFullManifest;
                }

                tFrom.remove(tManifest);
                _store.updateCollection(tFrom);

                Map<String,Object> tResponse = new HashMap<String,Object>();
                tResponse.put("code", pRes.SC_OK);
                tResponse.put("message", "Succesfully removed manifest from collection");
                this.sendJson(pRes, pRes.SC_OK, tResponse);
            } else {
                Map<String,Object> tResponse = new HashMap<String,Object>();
                tResponse.put("code", pRes.SC_UNAUTHORIZED);
                tResponse.put("message", "You can only edit your own collections unless you are Admin");
                this.sendJson(pRes, pRes.SC_UNAUTHORIZED, tResponse);
            }

        }

    }
    protected void sendJson(final HttpServletResponse pRes, final int pCode, final Map<String,Object> pPayload) throws IOException {
        pRes.setStatus(pCode);
        pRes.setContentType("application/json");
        pRes.setCharacterEncoding("UTF-8");
        JsonUtils.writePrettyPrint(pRes.getWriter(), pPayload);
    }

}
