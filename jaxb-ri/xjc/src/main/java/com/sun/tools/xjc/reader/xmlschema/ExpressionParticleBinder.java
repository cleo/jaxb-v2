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

package com.sun.tools.xjc.reader.xmlschema;

import java.util.Collection;

import com.sun.tools.xjc.model.CPropertyInfo;
import com.sun.tools.xjc.model.Model;
import com.sun.tools.xjc.model.Multiplicity;
import com.sun.tools.xjc.reader.RawTypeSet;
import com.sun.tools.xjc.reader.Ring;
import com.sun.tools.xjc.reader.gbind.ConnectedComponent;
import com.sun.tools.xjc.reader.gbind.Element;
import com.sun.tools.xjc.reader.gbind.Expression;
import com.sun.tools.xjc.reader.gbind.Graph;
import com.sun.tools.xjc.reader.xmlschema.bindinfo.BIProperty;
import com.sun.xml.bind.v2.model.core.WildcardMode;
import com.sun.xml.xsom.XSParticle;

/**
 * {@link ParticleBinder} that uses {@link ExpressionBuilder} et al for better,
 * more intuitive (but non spec-conforming) binding.
 *
 * @author Kohsuke Kawaguchi
 */
final class ExpressionParticleBinder extends ParticleBinder {
	public void build(XSParticle p, Collection<XSParticle> forcedProps) {
		// this class isn't about spec conformance, but
		// for the ease of use.
		// so we don't give a damn about 'forcedProps'.
		// although, for a future note, it's conceivable to expand
		// the binding algorithm to cover this notion.

		Expression tree = ExpressionBuilder.createTree(p);
		Graph g = new Graph(tree);
		for (ConnectedComponent cc : g) {
			buildProperty(cc);
		}
	}

	/**
	 * Builds a property ouf ot a connected component.
	 */
	private void buildProperty(ConnectedComponent cc) {
		StringBuilder propName = new StringBuilder(); // property name
		int nameTokenCount = 0; // combine only up to 3

		RawTypeSetBuilder rtsb = new RawTypeSetBuilder();
		for (Element e : cc) {
			GElement ge = (GElement) e;

			if (nameTokenCount < 3) {
				if (nameTokenCount != 0)
					propName.append("And");
				propName.append(makeJavaName(cc.isCollection(), ge.getPropertyNameSeed()));
				nameTokenCount++;
			}

			if (e instanceof GElementImpl) {
				GElementImpl ei = (GElementImpl) e;
				rtsb.elementDecl(ei.decl);
				continue;
			}
			if (e instanceof GWildcardElement) {
				GWildcardElement w = (GWildcardElement) e;
				rtsb.getRefs().add(Ring.get(Model.class).options.refFactory.initWildCardRef(w.isStrict() ? WildcardMode.STRICT : WildcardMode.SKIP));
				continue;
			}
			assert false : e; // no other kind should be here
		}

		Multiplicity m = Multiplicity.ONE;
		if (cc.isCollection())
			m = m.makeRepeated();
		if (!cc.isRequired())
			m = m.makeOptional();

		RawTypeSet rts = new RawTypeSet(rtsb.getRefs(), m);

		XSParticle p = findSourceParticle(cc);

		BIProperty cust = BIProperty.getCustomization(p);
		CPropertyInfo prop = cust.createElementOrReferenceProperty(propName.toString(), false, p, rts);
		getCurrentBean().addProperty(prop);
	}

	/**
	 * Finds a {@link XSParticle} that can serve as the representative property
	 * of the given {@link ConnectedComponent}.
	 *
	 * The representative property is used for reporting an error location and
	 * taking {@link BIProperty} customization. Right now, the algorithm is just
	 * pick the first one with {@link BIProperty}, but one can think of a better
	 * algorithm, such as taking a choice of (A|B) if CC={A,B}.
	 */
	private XSParticle findSourceParticle(ConnectedComponent cc) {
		XSParticle first = null;

		for (Element e : cc) {
			GElement ge = (GElement) e;
			for (XSParticle p : ge.particles) {
				if (first == null)
					first = p;
				if (getLocalPropCustomization(p) != null)
					return p;
			}
			// if there are multiple property customizations,
			// all but one will be left unused, which will be detected as an
			// error
			// later, so no need to check that now.
		}

		// if no customization was found, just use the first one.
		return first;
	}

	public boolean checkFallback(XSParticle p) {
		// this algorithm never falls back to 'getContent'.
		return false;
	}
}
