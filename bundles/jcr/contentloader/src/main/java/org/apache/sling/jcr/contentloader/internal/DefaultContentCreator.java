/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.jcr.contentloader.internal;

import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.StringTokenizer;
import java.util.Map.Entry;
import java.util.regex.Pattern;

import javax.jcr.Item;
import javax.jcr.Node;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.ValueFactory;

import org.apache.jackrabbit.api.jsr283.security.AccessControlEntry;
import org.apache.jackrabbit.api.jsr283.security.AccessControlList;
import org.apache.jackrabbit.api.jsr283.security.AccessControlManager;
import org.apache.jackrabbit.api.jsr283.security.AccessControlPolicy;
import org.apache.jackrabbit.api.jsr283.security.AccessControlPolicyIterator;
import org.apache.jackrabbit.api.jsr283.security.Privilege;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.jcr.base.util.AccessControlUtil;

/**
 * The <code>ContentLoader</code> creates the nodes and properties.
 * @since 2.0.4
 */
public class DefaultContentCreator implements ContentCreator {

	private PathEntry configuration;

    private final Pattern jsonDatePattern = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}[-+]{1}[0-9]{2}[:]{0,1}[0-9]{2}$");
    private final SimpleDateFormat jsonDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    private final Stack<Node> parentNodeStack = new Stack<Node>();

    /** The list of versionables. */
    private final List<Node> versionables = new ArrayList<Node>();

    /** Delayed references during content loading for the reference property. */
    private final Map<String, List<String>> delayedReferences = new HashMap<String, List<String>>();
    private final Map<String, String[]> delayedMultipleReferences = new HashMap<String, String[]>();

    private String defaultRootName;

    private Node rootNode;

    private boolean isRootNodeImport;

    private boolean ignoreOverwriteFlag = false;

    // default content type for createFile()
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    /** Helper class to get the mime type of a file. */
    private final ContentLoaderService jcrContentHelper;

    /** List of active import providers mapped by extension. */
    private Map<String, ImportProvider> importProviders;

    /** Optional list of created nodes (for uninstall) */
    private List<String> createdNodes;

    /**
     * A one time use seed to randomize the user location.
     */
    private static final long INSTANCE_SEED = System.currentTimeMillis();

    /**
     * The number of levels folder used to store a user, could be a configuration option.
     */
    private static final int STORAGE_LEVELS = 3;

    /**
     * Constructor.
     * @param jcrContentHelper Helper class to get the mime type of a file
     */
    public DefaultContentCreator(ContentLoaderService jcrContentHelper) {
        this.jcrContentHelper = jcrContentHelper;
    }

    /**
     * Initialize this component.
     * @param pathEntry The configuration for this import.
     * @param defaultImportProviders List of all import providers.
     * @param createdNodes Optional list to store new nodes (for uninstall)
     */
    public void init(final PathEntry pathEntry,
                     final Map<String, ImportProvider> defaultImportProviders,
                     final List<String> createdNodes) {
        this.configuration = pathEntry;
        // create list of allowed import providers
        this.importProviders = new HashMap<String, ImportProvider>();
        final Iterator<Map.Entry<String, ImportProvider>> entryIter = defaultImportProviders.entrySet().iterator();
        while ( entryIter.hasNext() ) {
            final Map.Entry<String, ImportProvider> current = entryIter.next();
            if (!configuration.isIgnoredImportProvider(current.getKey()) ) {
                importProviders.put(current.getKey(), current.getValue());
            }
        }
        this.createdNodes = createdNodes;
    }

    /**
     *
     * If the defaultRootName is null, we are in ROOT_NODE import mode.
     * @param parentNode
     * @param defaultRootName
     */
    public void prepareParsing(final Node parentNode,
                               final String defaultRootName) {
        this.parentNodeStack.clear();
        this.parentNodeStack.push(parentNode);
        this.defaultRootName = defaultRootName;
        this.rootNode = null;
        isRootNodeImport = defaultRootName == null;
    }

    /**
     * Get the list of versionable nodes.
     */
    public List<Node> getVersionables() {
        return this.versionables;
    }

    /**
     * Clear the content loader.
     */
    public void clear() {
        this.versionables.clear();
    }

    /**
     * Set the ignore overwrite flag.
     * @param flag
     */
    public void setIgnoreOverwriteFlag(boolean flag) {
        this.ignoreOverwriteFlag = flag;
    }

    /**
     * Get the created root node.
     */
    public Node getRootNode() {
        return this.rootNode;
    }

    /**
     * Get all active import providers.
     * @return A map of providers
     */
    public Map<String, ImportProvider> getImportProviders() {
        return this.importProviders;
    }

    /**
     * Return the import provider for the name
     * @param name The file name.
     * @return The provider or <code>null</code>
     */
    public ImportProvider getImportProvider(String name) {
        ImportProvider provider = null;
        final Iterator<String> ipIter = importProviders.keySet().iterator();
        while (provider == null && ipIter.hasNext()) {
            final String ext = ipIter.next();
            if (name.endsWith(ext)) {
                provider = importProviders.get(ext);
            }
        }
        return provider;
    }

    /**
     * Get the extension of the file name.
     * @param name The file name.
     * @return The extension a provider is registered for - or <code>null</code>
     */
    public String getImportProviderExtension(String name) {
        String providerExt = null;
        final Iterator<String> ipIter = importProviders.keySet().iterator();
        while (providerExt == null && ipIter.hasNext()) {
            final String ext = ipIter.next();
            if (name.endsWith(ext)) {
                providerExt = ext;
            }
        }
        return providerExt;
    }

    /**
     * @see org.apache.sling.jcr.contentloader.internal.ContentCreator#createNode(java.lang.String, java.lang.String, java.lang.String[])
     */
    public void createNode(String name,
                           String primaryNodeType,
                           String[] mixinNodeTypes)
    throws RepositoryException {
        final Node parentNode = this.parentNodeStack.peek();
        if ( name == null ) {
            if ( this.parentNodeStack.size() > 1 ) {
                throw new RepositoryException("Node needs to have a name.");
            }
            name = this.defaultRootName;
        }

        // if we are in root node import mode, we don't create the root top level node!
        if ( !isRootNodeImport || this.parentNodeStack.size() > 1 ) {
            // if node already exists but should be overwritten, delete it
            if (!this.ignoreOverwriteFlag && this.configuration.isOverwrite() && parentNode.hasNode(name)) {
                parentNode.getNode(name).remove();
            }

            // ensure repository node
            Node node;
            if (parentNode.hasNode(name)) {

                // use existing node
                node = parentNode.getNode(name);
            } else if (primaryNodeType == null) {

                // no explicit node type, use repository default
                node = parentNode.addNode(name);
                if ( this.createdNodes != null ) {
                    this.createdNodes.add(node.getPath());
                }

            } else {

                // explicit primary node type
                node = parentNode.addNode(name, primaryNodeType);
                if ( this.createdNodes != null ) {
                    this.createdNodes.add(node.getPath());
                }
            }

            // ammend mixin node types
            if (mixinNodeTypes != null) {
                for (final String mixin : mixinNodeTypes) {
                    if (!node.isNodeType(mixin)) {
                        node.addMixin(mixin);
                    }
                }
            }

            // check if node is versionable
            final boolean addToVersionables = this.configuration.isCheckin()
                                        && node.isNodeType("mix:versionable");
            if ( addToVersionables ) {
                this.versionables.add(node);
            }

            this.parentNodeStack.push(node);
            if ( this.rootNode == null ) {
                this.rootNode = node;
            }
        }
    }

    /**
     * @see org.apache.sling.jcr.contentloader.internal.ContentCreator#createProperty(java.lang.String, int, java.lang.String)
     */
    public void createProperty(String name, int propertyType, String value)
    throws RepositoryException {
        final Node node = this.parentNodeStack.peek();
        // check if the property already exists, don't overwrite it in this case
        if (node.hasProperty(name)
            && !node.getProperty(name).isNew()) {
            return;
        }

        if ( propertyType == PropertyType.REFERENCE ) {
            // need to resolve the reference
            String propPath = node.getPath() + "/" + name;
            String uuid = getUUID(node.getSession(), propPath, getAbsPath(node, value));
            if (uuid != null) {
                node.setProperty(name, uuid, propertyType);
            }

        } else if ("jcr:isCheckedOut".equals(name)) {

            // don't try to write the property but record its state
            // for later checkin if set to false
            final boolean checkedout = Boolean.valueOf(value);
            if (!checkedout) {
                if ( !this.versionables.contains(node) ) {
                    this.versionables.add(node);
                }
            }
        } else if ( propertyType == PropertyType.DATE ) {
            try {
              node.setProperty(name, parseDateString(value) );
            }
            catch (ParseException e) {
              // Fall back to default behaviour if this fails
              node.setProperty(name, value, propertyType);
            }
        } else {
            node.setProperty(name, value, propertyType);
        }
    }

    /**
     * @see org.apache.sling.jcr.contentloader.internal.ContentCreator#createProperty(java.lang.String, int, java.lang.String[])
     */
    public void createProperty(String name, int propertyType, String[] values)
    throws RepositoryException {
        final Node node = this.parentNodeStack.peek();
        // check if the property already exists, don't overwrite it in this case
        if (node.hasProperty(name)
            && !node.getProperty(name).isNew()) {
            return;
        }
        if ( propertyType == PropertyType.REFERENCE ) {
            String propPath = node.getPath() + "/" + name;

            boolean hasAll = true;
            String[] uuids = new String[values.length];
            String[] uuidOrPaths = new String[values.length];
            for (int i = 0; i < values.length; i++) {
                uuids[i] = getUUID(node.getSession(), propPath, getAbsPath(node, values[i]));
                uuidOrPaths[i] = uuids[i] != null ? uuids[i] : getAbsPath(node, values[i]);
                if (uuids[i] == null) hasAll = false;
            }

            node.setProperty(name, uuids, propertyType);

            if (!hasAll) {
                delayedMultipleReferences.put(propPath, uuidOrPaths);
            }
        } else if ( propertyType == PropertyType.DATE ) {
            try {
              // This modification is to remove the colon in the JSON Timezone
              ValueFactory valueFactory = node.getSession().getValueFactory();
              Value[] jcrValues = new Value[values.length];

              for (int i = 0; i < values.length; i++) {
                jcrValues[i] = valueFactory.createValue( parseDateString( values[i] ) );
              }

              node.setProperty(name, jcrValues, propertyType);
            }
            catch (ParseException e) {
              // If this failes, fallback to the default
              jcrContentHelper.log.warn("Could not create dates for property, fallingback to defaults", e);
              node.setProperty(name, values, propertyType);
            }

        } else {
            node.setProperty(name, values, propertyType);
        }
    }

    protected Value createValue(final ValueFactory factory, Object value) {
        if ( value == null ) {
            return null;
        }
        if ( value instanceof Long ) {
            return factory.createValue((Long)value);
        } else if ( value instanceof Date ) {
            final Calendar c = Calendar.getInstance();
            c.setTime((Date)value);
            return factory.createValue(c);
        } else if ( value instanceof Calendar ) {
            return factory.createValue((Calendar)value);
        } else if ( value instanceof Double ) {
            return factory.createValue((Double)value);
        } else if ( value instanceof Boolean ) {
            return factory.createValue((Boolean)value);
        } else if ( value instanceof InputStream ) {
            return factory.createValue((InputStream)value);
        } else {
            return factory.createValue(value.toString());
        }

    }
    /**
     * @see org.apache.sling.jcr.contentloader.internal.ContentCreator#createProperty(java.lang.String, java.lang.Object)
     */
    public void createProperty(String name, Object value)
    throws RepositoryException {
        final Node node = this.parentNodeStack.peek();
        // check if the property already exists, don't overwrite it in this case
        if (node.hasProperty(name)
            && !node.getProperty(name).isNew()) {
            return;
        }
        if ( value == null ) {
            if ( node.hasProperty(name) ) {
                node.getProperty(name).remove();
            }
        } else {
            final Value jcrValue = this.createValue(node.getSession().getValueFactory(), value);
            node.setProperty(name, jcrValue);
        }
    }

    /**
     * @see org.apache.sling.jcr.contentloader.internal.ContentCreator#createProperty(java.lang.String, java.lang.Object[])
     */
    public void createProperty(String name, Object[] values)
    throws RepositoryException {
        final Node node = this.parentNodeStack.peek();
        // check if the property already exists, don't overwrite it in this case
        if (node.hasProperty(name)
            && !node.getProperty(name).isNew()) {
            return;
        }
        if ( values == null || values.length == 0 ) {
            if ( node.hasProperty(name) ) {
                node.getProperty(name).remove();
            }
        } else {
            final Value[] jcrValues = new Value[values.length];
            for(int i = 0; i < values.length; i++) {
                jcrValues[i] = this.createValue(node.getSession().getValueFactory(), values[i]);
            }
            node.setProperty(name, jcrValues);
        }
    }

    /**
     * @see org.apache.sling.jcr.contentloader.internal.ContentCreator#finishNode()
     */
    public void finishNode()
    throws RepositoryException {
        final Node node = this.parentNodeStack.pop();
        // resolve REFERENCE property values pointing to this node
        resolveReferences(node);
    }

    private String getAbsPath(Node node, String path) throws RepositoryException {
        if (path.startsWith("/")) return path;

        while (path.startsWith("../")) {
            path = path.substring(3);
            node = node.getParent();
        }

        while (path.startsWith("./")) {
            path = path.substring(2);
        }

        return node.getPath() + "/" + path;
    }

    private String getUUID(Session session, String propPath,
                          String referencePath)
    throws RepositoryException {
        if (session.itemExists(referencePath)) {
            Item item = session.getItem(referencePath);
            if (item.isNode()) {
                Node refNode = (Node) item;
                if (refNode.isNodeType("mix:referenceable")) {
                    return refNode.getUUID();
                }
            }
        } else {
            // not existing yet, keep for delayed setting
            List<String> current = delayedReferences.get(referencePath);
            if (current == null) {
                current = new ArrayList<String>();
                delayedReferences.put(referencePath, current);
            }
            current.add(propPath);
        }

        // no UUID found
        return null;
    }

    private void resolveReferences(Node node) throws RepositoryException {
        List<String> props = delayedReferences.remove(node.getPath());
        if (props == null || props.size() == 0) {
            return;
        }

        // check whether we can set at all
        if (!node.isNodeType("mix:referenceable")) {
            return;
        }

        Session session = node.getSession();
        String uuid = node.getUUID();

        for (String property : props) {
            String name = getName(property);
            Node parentNode = getParentNode(session, property);
            if (parentNode != null) {
                if (parentNode.hasProperty(name) && parentNode.getProperty(name).getDefinition().isMultiple()) {
                    boolean hasAll = true;
                    String[] uuidOrPaths = delayedMultipleReferences.get(property);
                    String[] uuids = new String[uuidOrPaths.length];
                    for (int i = 0; i < uuidOrPaths.length; i++) {
                        // is the reference still a path
                        if (uuidOrPaths[i].startsWith("/")) {
                            if (uuidOrPaths[i].equals(node.getPath())) {
                                uuidOrPaths[i] = uuid;
                                uuids[i] = uuid;
                            } else {
                                uuids[i] = null;
                                hasAll = false;
                            }
                        } else {
                            uuids[i] = uuidOrPaths[i];
                        }
                    }
                    parentNode.setProperty(name, uuids, PropertyType.REFERENCE);

                    if (hasAll) {
                        delayedMultipleReferences.remove(property);
                    }
                } else {
                    parentNode.setProperty(name, uuid, PropertyType.REFERENCE);
                }
            }
        }
    }

    /**
     * Gets the name part of the <code>path</code>. The name is
     * the part of the path after the last slash (or the complete path if no
     * slash is contained).
     *
     * @param path The path from which to extract the name part.
     * @return The name part.
     */
    private String getName(String path) {
        int lastSlash = path.lastIndexOf('/');
        String name = (lastSlash < 0) ? path : path.substring(lastSlash + 1);

        return name;
    }

    private Node getParentNode(Session session, String path)
            throws RepositoryException {
        int lastSlash = path.lastIndexOf('/');

        // not an absolute path, cannot find parent
        if (lastSlash < 0) {
            return null;
        }

        // node below root
        if (lastSlash == 0) {
            return session.getRootNode();
        }

        // item in the hierarchy
        path = path.substring(0, lastSlash);
        if (!session.itemExists(path)) {
            return null;
        }

        Item item = session.getItem(path);
        return (item.isNode()) ? (Node) item : null;
    }

    private Calendar parseDateString(String value) throws ParseException {
      if (jsonDatePattern.matcher(value).matches()) {
        String modifiedJsonDate = value;

        // This modification is to remove the colon in the JSON Timezone
        // to match the Java Version
        if (value.lastIndexOf(":") == 26) {
          modifiedJsonDate = value.substring(0, 26) + value.substring(27);
        }

        Calendar cal = Calendar.getInstance();
        cal.setTime( jsonDateFormat.parse( modifiedJsonDate ) );

        return cal;
      }

      return null;
    }

    /**
     * @see org.apache.sling.jcr.contentloader.internal.ContentCreator#createFileAndResourceNode(java.lang.String, java.io.InputStream, java.lang.String, long)
     */
    public void createFileAndResourceNode(String name,
                                          InputStream data,
                                          String mimeType,
                                          long lastModified)
    throws RepositoryException {
        int lastSlash = name.lastIndexOf('/');
        name = (lastSlash < 0) ? name : name.substring(lastSlash + 1);
        final Node parentNode = this.parentNodeStack.peek();

        // if node already exists but should be overwritten, delete it
        if (this.configuration.isOverwrite() && parentNode.hasNode(name)) {
            parentNode.getNode(name).remove();
        } else if (parentNode.hasNode(name)) {
            this.parentNodeStack.push(parentNode.getNode(name));
            this.parentNodeStack.push(parentNode.getNode(name).getNode("jcr:content"));
            return;
        }

        // ensure content type
        if (mimeType == null) {
            mimeType = jcrContentHelper.getMimeType(name);
            if (mimeType == null) {
                jcrContentHelper.log.info(
                    "createFile: Cannot find content type for {}, using {}",
                    name, DEFAULT_CONTENT_TYPE);
                mimeType = DEFAULT_CONTENT_TYPE;
            }
        }

        // ensure sensible last modification date
        if (lastModified <= 0) {
            lastModified = System.currentTimeMillis();
        }

        this.createNode(name, "nt:file", null);
        this.createNode("jcr:content", "nt:resource", null);
        this.createProperty("jcr:mimeType", mimeType);
        this.createProperty("jcr:lastModified", lastModified);
        this.createProperty("jcr:data", data);
    }

    /**
     * @see org.apache.sling.jcr.contentloader.internal.ContentCreator#switchCurrentNode(java.lang.String, java.lang.String)
     */
    public boolean switchCurrentNode(String subPath, String newNodeType)
    throws RepositoryException {
        if ( subPath.startsWith("/") ) {
            subPath = subPath.substring(1);
        }
        final StringTokenizer st = new StringTokenizer(subPath, "/");
        Node node = this.parentNodeStack.peek();
        while ( st.hasMoreTokens() ) {
            final String token = st.nextToken();
            if ( !node.hasNode(token) ) {
                if ( newNodeType == null ) {
                    return false;
                }
                final Node n = node.addNode(token, newNodeType);
                if ( this.createdNodes != null ) {
                    this.createdNodes.add(n.getPath());
                }
            }
            node = node.getNode(token);
        }
        this.parentNodeStack.push(node);
        return true;
    }


	/* (non-Javadoc)
	 * @see org.apache.sling.jcr.contentloader.internal.ContentCreator#createGroup(java.lang.String, java.lang.String[], java.util.Map)
	 */
	public void createGroup(final String name, String[] members,
			Map<String, Object> extraProperties) throws RepositoryException {

		final Node parentNode = this.parentNodeStack.peek();
		Session session = parentNode.getSession();

        UserManager userManager = AccessControlUtil.getUserManager(session);
        Authorizable authorizable = userManager.getAuthorizable(name);
        if (authorizable == null) {
            //principal does not exist yet, so create it
        	Group group = userManager.createGroup(new Principal() {
                    public String getName() {
                        return name;
                    }
                },
                hashPath(name));
        	authorizable = group;
        } else {
        	//principal already exists, check to make sure it is the expected type
        	if (!authorizable.isGroup()) {
                throw new RepositoryException(
                        "A user already exists with the requested name: "
                            + name);
            }
    		//group already exists so just update it below
        }
        //update the group members
        if (members != null) {
        	Group group = (Group)authorizable;
        	for (String member : members) {
        		Authorizable memberAuthorizable = userManager.getAuthorizable(member);
        		if (memberAuthorizable != null) {
        			group.addMember(memberAuthorizable);
        		}
        	}
        }
        if (extraProperties != null) {
        	ValueFactory valueFactory = session.getValueFactory();
        	Set<Entry<String, Object>> entrySet = extraProperties.entrySet();
        	for (Entry<String, Object> entry : entrySet) {
        		Value value = createValue(valueFactory, entry.getValue());
        		authorizable.setProperty(name, value);
			}
        }
	}

	/* (non-Javadoc)
	 * @see org.apache.sling.jcr.contentloader.internal.ContentCreator#createUser(java.lang.String, java.lang.String, java.util.Map)
	 */
	public void createUser(final String name, String password,
			Map<String, Object> extraProperties) throws RepositoryException {
		final Node parentNode = this.parentNodeStack.peek();
		Session session = parentNode.getSession();

        UserManager userManager = AccessControlUtil.getUserManager(session);
        Authorizable authorizable = userManager.getAuthorizable(name);
        if (authorizable == null) {
            //principal does not exist yet, so create it
        	String digestedPassword = jcrContentHelper.digestPassword(password);
        	User user = userManager.createUser(name,
        			digestedPassword,
        			new Principal() {
						public String getName() {
							return name;
						}
		        	},
		        	hashPath(name));
        	authorizable = user;
        } else {
        	//principal already exists, check to make sure it is the expected type
        	if (authorizable.isGroup()) {
                throw new RepositoryException(
                        "A group already exists with the requested name: "
                            + name);
            }
    		//user already exists so just update it below
        }
        if (extraProperties != null) {
        	ValueFactory valueFactory = session.getValueFactory();
        	Set<Entry<String, Object>> entrySet = extraProperties.entrySet();
        	for (Entry<String, Object> entry : entrySet) {
        		Value value = createValue(valueFactory, entry.getValue());
        		authorizable.setProperty(name, value);
			}
        }
	}

	/**
	 * @param item
	 * @return a parent path fragment for the item.
	 */
	protected String hashPath(String item) throws RepositoryException {
		try {
			String hash = digest("sha1", (INSTANCE_SEED + item).getBytes("UTF-8"));
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < STORAGE_LEVELS; i++) {
				sb.append(hash, i * 2, (i * 2) + 2).append("/");
			}
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new RepositoryException("Unable to hash the path.", e);
		} catch (UnsupportedEncodingException e) {
			throw new RepositoryException("Unable to hash the path.", e);
		}
	}


    /* (non-Javadoc)
	 * @see org.apache.sling.jcr.contentloader.internal.ContentCreator#createAce(java.lang.String, java.lang.String, java.lang.String[], java.lang.String[])
	 */
	public void createAce(String principalId,
			String[] grantedPrivilegeNames, String[] deniedPrivilegeNames)
			throws RepositoryException {
		final Node parentNode = this.parentNodeStack.peek();
		Session session = parentNode.getSession();

		UserManager userManager = AccessControlUtil.getUserManager(session);
		Authorizable authorizable = userManager.getAuthorizable(principalId);
		if (authorizable == null) {
			throw new RepositoryException("No principal found for id: " + principalId);
		}

		String resourcePath = parentNode.getPath();

		AccessControlManager accessControlManager = AccessControlUtil.getAccessControlManager(session);
		AccessControlList updatedAcl = null;
		AccessControlPolicy[] policies = accessControlManager.getPolicies(resourcePath);
		for (AccessControlPolicy policy : policies) {
		  if (policy instanceof AccessControlList) {
		    updatedAcl = (AccessControlList)policy;
		    break;
		  }
		}
		if (updatedAcl == null) {
		  AccessControlPolicyIterator applicablePolicies = accessControlManager.getApplicablePolicies(resourcePath);
		  while (applicablePolicies.hasNext()) {
		    AccessControlPolicy policy = applicablePolicies.nextAccessControlPolicy();
		    if (policy instanceof AccessControlList) {
		      updatedAcl = (AccessControlList)policy;
		    }
		  }
		}
		if (updatedAcl == null) {
			throw new RepositoryException("Unable to find or create an access control policy to update for " + resourcePath);
		}

		Set<String> postedPrivilegeNames = new HashSet<String>();
		if (grantedPrivilegeNames != null) {
			postedPrivilegeNames.addAll(Arrays.asList(grantedPrivilegeNames));
		}
		if (deniedPrivilegeNames != null) {
			postedPrivilegeNames.addAll(Arrays.asList(deniedPrivilegeNames));
		}

		List<Privilege> preserveGrantedPrivileges = new ArrayList<Privilege>();
		List<Privilege> preserveDeniedPrivileges = new ArrayList<Privilege>();

		//keep track of the existing Aces for the target principal
		AccessControlEntry[] accessControlEntries = updatedAcl.getAccessControlEntries();
		List<AccessControlEntry> oldAces = new ArrayList<AccessControlEntry>();
		for (AccessControlEntry ace : accessControlEntries) {
			if (principalId.equals(ace.getPrincipal().getName())) {
				oldAces.add(ace);

				boolean isAllow = AccessControlUtil.isAllow(ace);
				Privilege[] privileges = ace.getPrivileges();
				for (Privilege privilege : privileges) {
					String privilegeName = privilege.getName();
					if (!postedPrivilegeNames.contains(privilegeName)) {
						//this privilege was not posted, so record the existing state to be
						// preserved when the ACE is re-created below
						if (isAllow) {
							preserveGrantedPrivileges.add(privilege);
						} else {
							preserveDeniedPrivileges.add(privilege);
						}
					}
				}
			}
		}

		//remove the old aces
		if (!oldAces.isEmpty()) {
			for (AccessControlEntry ace : oldAces) {
				updatedAcl.removeAccessControlEntry(ace);
			}
		}

		//add a fresh ACE with the granted privileges
		List<Privilege> grantedPrivilegeList = new ArrayList<Privilege>();
		if (grantedPrivilegeNames != null) {
		  for (String name : grantedPrivilegeNames) {
			  if (name.length() == 0) {
				  continue; //empty, skip it.
			  }
			  Privilege privilege = accessControlManager.privilegeFromName(name);
			  grantedPrivilegeList.add(privilege);
	    }
		}
		//add the privileges that should be preserved
		grantedPrivilegeList.addAll(preserveGrantedPrivileges);

		if (grantedPrivilegeList.size() > 0) {
			Principal principal = authorizable.getPrincipal();
			updatedAcl.addAccessControlEntry(principal, grantedPrivilegeList.toArray(new Privilege[grantedPrivilegeList.size()]));
		}

		//if the authorizable is a user (not a group) process any denied privileges
		if (!authorizable.isGroup()) {
			//add a fresh ACE with the denied privileges
			List<Privilege> deniedPrivilegeList = new ArrayList<Privilege>();
			if (deniedPrivilegeNames != null) {
			  for (String name : deniedPrivilegeNames) {
				  if (name.length() == 0) {
					  continue; //empty, skip it.
				  }
				  Privilege privilege = accessControlManager.privilegeFromName(name);
				  deniedPrivilegeList.add(privilege);
			  }
			}
			//add the privileges that should be preserved
			deniedPrivilegeList.addAll(preserveDeniedPrivileges);
			if (deniedPrivilegeList.size() > 0) {
				Principal principal = authorizable.getPrincipal();
				AccessControlUtil.addEntry(updatedAcl, principal, deniedPrivilegeList.toArray(new Privilege[deniedPrivilegeList.size()]), false);
			}
		}

		accessControlManager.setPolicy(resourcePath, updatedAcl);
	}

	/**
     * used for the md5
     */
    private static final char[] hexTable = "0123456789abcdef".toCharArray();

    /**
     * Digest the plain string using the given algorithm.
     *
     * @param algorithm The alogrithm for the digest. This algorithm must be
     *                  supported by the MessageDigest class.
     * @param data      the data to digest with the given algorithm
     * @return The digested plain text String represented as Hex digits.
     * @throws java.security.NoSuchAlgorithmException if the desired algorithm is not supported by
     *                                  the MessageDigest class.
     */
    public static String digest(String algorithm, byte[] data)
            throws NoSuchAlgorithmException {

        MessageDigest md = MessageDigest.getInstance(algorithm);
        byte[] digest = md.digest(data);
        StringBuffer res = new StringBuffer(digest.length * 2);
        for (int i = 0; i < digest.length; i++) {
            byte b = digest[i];
            res.append(hexTable[(b >> 4) & 15]);
            res.append(hexTable[b & 15]);
        }
        return res.toString();
    }
}
