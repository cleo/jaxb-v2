/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 1997-2011 Oracle and/or its affiliates. All rights reserved.
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

package com.sun.tools.xjc.reader;

import static com.sun.tools.xjc.model.CElementPropertyInfo.CollectionMode.NOT_REPEATED;
import static com.sun.tools.xjc.model.CElementPropertyInfo.CollectionMode.REPEATED_ELEMENT;
import static com.sun.tools.xjc.model.CElementPropertyInfo.CollectionMode.REPEATED_VALUE;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.activation.MimeType;

import com.sun.tools.xjc.model.CElementPropertyInfo;
import com.sun.tools.xjc.model.CReferencePropertyInfo;
import com.sun.tools.xjc.model.CTypeRef;
import com.sun.tools.xjc.model.Multiplicity;
import com.sun.tools.xjc.model.nav.NType;
import com.sun.tools.xjc.reader.xmlschema.ref.Ref;
import com.sun.xml.bind.v2.model.core.ID;

/**
 * Set of {@link Ref}.
 *
 * @author Kohsuke Kawaguchi
 */
public final class RawTypeSet {

	public final Set<Ref> refs;

	/**
	 * True if this type set can form references to types.
	 */
	public final Mode canBeTypeRefs;

	/**
	 * The occurence of the whole references.
	 */
	public final Multiplicity mul;

	// computed inside canBeTypeRefs()
	private CElementPropertyInfo.CollectionMode collectionMode;

	/**
	 * Should be called from one of the raw type set builders.
	 */
	public RawTypeSet(Set<Ref> refs, Multiplicity m) {
		this.refs = refs;
		mul = m;
		canBeTypeRefs = canBeTypeRefs();
	}

	public CElementPropertyInfo.CollectionMode getCollectionMode() {
		return collectionMode;
	}

	public boolean isRequired() {
		return mul.min.compareTo(BigInteger.ZERO) == 1;
	}

	/**
	 * Represents the possible binding option for this {@link RawTypeSet}.
	 */
	public enum Mode {
		/**
		 * This {@link RawTypeSet} can be either an reference property or an
		 * element property, and XJC recommends element property.
		 */
		SHOULD_BE_TYPEREF(0),
		/**
		 * This {@link RawTypeSet} can be either an reference property or an
		 * element property, and XJC recommends reference property.
		 */
		CAN_BE_TYPEREF(1),
		/**
		 * This {@link RawTypeSet} can be only bound to a reference property.
		 */
		MUST_BE_REFERENCE(2);

		private final int rank;

		Mode(int rank) {
			this.rank = rank;
		}

		Mode or(Mode that) {
			switch (Math.max(this.rank, that.rank)) {
			case 0:
				return SHOULD_BE_TYPEREF;
			case 1:
				return CAN_BE_TYPEREF;
			case 2:
				return MUST_BE_REFERENCE;
			}
			throw new AssertionError();
		}
	}

	/**
	 * Returns true if {@link #refs} can form refs of types.
	 *
	 * If there are multiple {@link Ref}s with the same type, we cannot make
	 * them into type refs. Or if any of the {@link Ref} says they cannot be in
	 * type refs, we cannot do that either.
	 *
	 * TODO: just checking if the refs are the same is not suffice. If two refs
	 * derive from each other, they cannot form a list of refs (because of a
	 * possible ambiguity).
	 */
	private Mode canBeTypeRefs() {
		Set<NType> types = new HashSet<NType>();

		collectionMode = mul.isAtMostOnce() ? NOT_REPEATED : REPEATED_ELEMENT;

		// the way we compute this is that we start from the most optimistic
		// value,
		// and then gradually degrade as we find something problematic.
		Mode mode = Mode.SHOULD_BE_TYPEREF;

		for (Ref r : refs) {
			mode = mode.or(r.canBeType(this));
			if (mode == Mode.MUST_BE_REFERENCE)
				return mode; // no need to continue the processing

			if (!types.add(r.toTypeRef(null).getTarget().getType()))
				return Mode.MUST_BE_REFERENCE; // collision
			if (r.isListOfValues()) {
				if (refs.size() > 1 || !mul.isAtMostOnce())
					return Mode.MUST_BE_REFERENCE; // restriction on @XmlList
				collectionMode = REPEATED_VALUE;
			}
		}
		return mode;
	}

	public void addTo(CElementPropertyInfo prop) {
		assert canBeTypeRefs != Mode.MUST_BE_REFERENCE;
		if (mul.isZero())
			return; // the property can't have any value

		List<CTypeRef> dst = prop.getTypes();
		for (Ref t : refs)
			dst.add(t.toTypeRef(prop));
	}

	public void addTo(CReferencePropertyInfo prop) {
		if (mul.isZero())
			return; // the property can't have any value
		for (Ref t : refs)
			t.toElementRef(prop);
	}

	public ID id() {
		for (Ref t : refs) {
			ID id = t.id();
			if (id != ID.NONE)
				return id;
		}
		return ID.NONE;
	}

	public MimeType getExpectedMimeType() {
		for (Ref t : refs) {
			MimeType mt = t.getExpectedMimeType();
			if (mt != null)
				return mt;
		}
		return null;
	}

}
