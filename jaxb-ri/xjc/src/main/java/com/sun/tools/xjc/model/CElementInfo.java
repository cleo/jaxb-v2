/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 1997-2014 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package com.sun.tools.xjc.model;

import static com.sun.tools.xjc.model.CElementPropertyInfo.CollectionMode.NOT_REPEATED;
import static com.sun.tools.xjc.model.CElementPropertyInfo.CollectionMode.REPEATED_VALUE;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.bind.JAXBElement;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.namespace.QName;

import org.xml.sax.Locator;

import com.sun.codemodel.JPackage;
import com.sun.codemodel.JType;
import com.sun.istack.Nullable;
import com.sun.tools.xjc.model.nav.NClass;
import com.sun.tools.xjc.model.nav.NType;
import com.sun.tools.xjc.model.nav.NavigatorImpl;
import com.sun.tools.xjc.outline.Outline;
import com.sun.tools.xjc.reader.Ring;
import com.sun.tools.xjc.reader.xmlschema.BGMBuilder;
import com.sun.tools.xjc.reader.xmlschema.bindinfo.BIDeclaration;
import com.sun.tools.xjc.reader.xmlschema.bindinfo.BIFactoryMethod;
import com.sun.tools.xjc.reader.xmlschema.bindinfo.BIInlineBinaryData;
import com.sun.tools.xjc.reader.xmlschema.bindinfo.BIProperty;
import com.sun.tools.xjc.reader.xmlschema.bindinfo.BindInfo;
import com.sun.xml.bind.v2.model.core.ElementInfo;
import com.sun.xml.xsom.XSElementDecl;
import com.sun.xml.xsom.XmlString;
import com.sun.xml.xsom.impl.AnnotationImpl;
import com.sun.xml.xsom.impl.ElementDecl;

/**
 * {@link ElementInfo} implementation for the compile-time model.
 *
 * <p>
 * As an NType, it represents the Java representation of this element (either
 * JAXBElement&lt;T> or Foo).
 *
 * @author Kohsuke Kawaguchi
 */
public final class CElementInfo extends AbstractCElement implements ElementInfo<NType, NClass>, NType, CClassInfoParent {

	private final QName tagName;

	/**
	 * Represents {@code JAXBElement&lt;ContentType>}.
	 */
	private NType type;

	/**
	 * If this element produces its own class, the short name of that class.
	 * Otherwise null.
	 */
	private String className;

	/**
	 * If this element is global, the element info is considered to be
	 * package-level, and this points to the package in which this element lives
	 * in.
	 *
	 * <p>
	 * For local elements, this points to the parent {@link CClassInfo}.
	 */
	public final CClassInfoParent parent;

	private CElementInfo substitutionHead;

	/**
	 * Lazily computed.
	 */
	private Set<CElementInfo> substitutionMembers;

	/**
	 * {@link Model} that owns this object.
	 */
	private final Model model;

	private CElementPropertyInfo property;

	/**
	 * Custom {@link #getSqueezedName() squeezed name}, if any.
	 */
	private /* almost final */ @Nullable String squeezedName;

	private @Nullable String squeezedNameHelper;

	/**
	 * Creates an element in the given parent.
	 *
	 * <p>
	 * When using this construction,
	 * {@link #initContentType(TypeUse, XSElementDecl, XmlString)} must not be
	 * invoked.
	 */
	public CElementInfo(Model model, QName tagName, CClassInfoParent parent, TypeUse contentType, XmlString defaultValue, XSElementDecl source,
			CCustomizations customizations, Locator location) {
		super(model, source, location, customizations);
		this.tagName = tagName;
		this.model = model;
		this.parent = parent;
		if (contentType != null)
			initContentType(contentType, source, defaultValue);

		setSqueezedNameHelper(contentType, source);
		model.add(this);
	}

	/**
	 * Set the squeezed name helper if the source definition had a binding added
	 * to it
	 * 
	 * @param contentType
	 * @param source
	 */
	private void setSqueezedNameHelper(TypeUse contentType, XSElementDecl source) {
		if (contentType instanceof CClassInfo && source instanceof ElementDecl) {
			AnnotationImpl annImpl = ((AnnotationImpl) ((ElementDecl) source).getAnnotation());
			if (annImpl != null) {
				Object o = annImpl.getAnnotation();
				if (o instanceof BindInfo) {
					BindInfo info = (BindInfo) o;
					for (BIDeclaration biDecl : info.getDecls()) {
						if (biDecl instanceof BIProperty) {
							String[] contentTypeStr = contentType.toString().split("\\.");
							squeezedNameHelper = contentTypeStr[contentTypeStr.length - 1];
							return;
						}
					}
				}
			}
		}
	}

	/**
	 * Creates an element with a class in the given parent.
	 *
	 * <p>
	 * When using this construction, the caller must use
	 * {@link #initContentType(TypeUse, XSElementDecl, XmlString)} to fill in
	 * the content type later.
	 *
	 * This is to avoid a circular model construction dependency between
	 * buidling a type inside an element and element itself. To build a content
	 * type, you need to have {@link CElementInfo} for a parent, so we can't
	 * take it as a constructor parameter.
	 */
	public CElementInfo(Model model, QName tagName, CClassInfoParent parent, String className, CCustomizations customizations, Locator location) {
		this(model, tagName, parent, null, null, null, customizations, location);
		this.className = className;
	}

	public void initContentType(TypeUse contentType, @Nullable XSElementDecl source, XmlString defaultValue) {
		assert this.property == null; // must not be called twice

		this.property = new CElementPropertyInfo("Value", contentType.isCollection() ? REPEATED_VALUE : NOT_REPEATED, contentType.idUse(),
				contentType.getExpectedMimeType(), source, null, getLocator(), true);
		this.property.setAdapter(contentType.getAdapterUse());
		BIInlineBinaryData.handle(source, property);
		property.getTypes().add(new CTypeRef(contentType.getInfo(), tagName, CTypeRef.getSimpleTypeName(source), true, defaultValue));
		this.type = NavigatorImpl.createParameterizedType(NavigatorImpl.theInstance.ref(JAXBElement.class), getContentInMemoryType());

		BIFactoryMethod factoryMethod = Ring.get(BGMBuilder.class).getBindInfo(source).get(BIFactoryMethod.class);
		if (factoryMethod != null) {
			factoryMethod.markAsAcknowledged();
			this.squeezedName = factoryMethod.name;
		}

	}

	public final String getDefaultValue() {
		return getProperty().getTypes().get(0).getDefaultValue();
	}

	public final JPackage _package() {
		return parent.getOwnerPackage();
	}

	@Override
	public CNonElement getContentType() {
		return getProperty().ref().get(0);
	}

	@Override
	public NType getContentInMemoryType() {
		if (getProperty().getAdapter() == null) {
			NType itemType = getContentType().getType();
			if (!property.isCollection())
				return itemType;

			return NavigatorImpl.createParameterizedType(List.class, itemType);
		} else {
			return getProperty().getAdapter().customType;
		}
	}

	@Override
	public CElementPropertyInfo getProperty() {
		return property;
	}

	@Override
	public CClassInfo getScope() {
		if (parent instanceof CClassInfo)
			return (CClassInfo) parent;
		return null;
	}

	/**
	 * @deprecated why are you calling a method that returns this?
	 */
	@Deprecated
	@Override
	public NType getType() {
		return this;
	}

	@Override
	public QName getElementName() {
		return tagName;
	}

	@Override
	public JType toType(Outline o, Aspect aspect) {
		if (className == null)
			return type.toType(o, aspect);
		else
			return o.getElement(this).implClass;
	}

	/**
	 * Returns the "squeezed name" of this element.
	 *
	 * @see CClassInfo#getSqueezedName()
	 */
	@XmlElement
	public String getSqueezedName() {
		if (squeezedName != null)
			return squeezedName;

		StringBuilder b = new StringBuilder();
		CClassInfo s = getScope();
		if (s != null)
			b.append(s.getSqueezedName());
		if (className != null)
			b.append(className);
		else if (squeezedNameHelper != null)
			b.append(model.getNameConverter().toClassName(squeezedNameHelper));
		else
			b.append(model.getNameConverter().toClassName(tagName.getLocalPart()));
		return b.toString();
	}

	@Override
	public CElementInfo getSubstitutionHead() {
		return substitutionHead;
	}

	@Override
	public Collection<CElementInfo> getSubstitutionMembers() {
		if (substitutionMembers == null)
			return Collections.emptyList();
		else
			return substitutionMembers;
	}

	public void setSubstitutionHead(CElementInfo substitutionHead) {
		// don't set it twice
		assert this.substitutionHead == null;
		assert substitutionHead != null;
		this.substitutionHead = substitutionHead;

		if (substitutionHead.substitutionMembers == null)
			substitutionHead.substitutionMembers = new HashSet<CElementInfo>();
		substitutionHead.substitutionMembers.add(this);
	}

	@Override
	public boolean isBoxedType() {
		return false;
	}

	@Override
	public String fullName() {
		if (className == null)
			return type.fullName();
		else {
			String r = parent.fullName();
			if (r.length() == 0)
				return className;
			else
				return r + '.' + className;
		}
	}

	@Override
	public <T> T accept(Visitor<T> visitor) {
		return visitor.onElement(this);
	}

	@Override
	public JPackage getOwnerPackage() {
		return parent.getOwnerPackage();
	}

	public String shortName() {
		return className;
	}

	/**
	 * True if this element has its own class (as opposed to be represented as
	 * an instance of {@link JAXBElement}.
	 */
	public boolean hasClass() {
		return className != null;
	}
}
