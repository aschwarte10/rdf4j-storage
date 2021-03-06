/*******************************************************************************
 * Copyright (c) 2016 Eclipse RDF4J contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *******************************************************************************/

package org.eclipse.rdf4j.sail.shacl.planNodes;

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.sail.SailException;

/**
 * @author Håvard Ottestad
 */
public class DirectTupleFromFilter implements PlanNode, PushBasedPlanNode, SupportsDepthProvider {


	private CloseableIteration<Tuple, SailException> parentIterator;

	Tuple next = null;
	private DepthProvider depthProvider;

	@Override
	public CloseableIteration<Tuple, SailException> iterator() {
		return new CloseableIteration<Tuple, SailException>() {

			private void calculateNext() {
				while (next == null && parentIterator.hasNext()) {
					parentIterator.next();
				}
			}

			@Override
			public void close() throws SailException {
				parentIterator.close();
			}

			@Override
			public boolean hasNext() throws SailException {
				calculateNext();
				return next != null;
			}

			@Override
			public Tuple next() throws SailException {
				calculateNext();
				Tuple temp = next;
				next = null;

				return temp;
			}


			@Override
			public void remove() throws SailException {

			}
		};
	}

	@Override
	public int depth() {
		return depthProvider.depth() + 1;
	}

	@Override
	public void push(Tuple t) {
		next = t;
	}

	@Override
	public void parentIterator(CloseableIteration<Tuple, SailException> iterator) {
		parentIterator = iterator;
	}


	@Override
	public void receiveDepthProvider(DepthProvider depthProvider) {
		this.depthProvider = depthProvider;
	}
}
