/*******************************************************************************
 * Copyright (c) 2016 Eclipse RDF4J contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *******************************************************************************/

package org.eclipse.rdf4j.sail.shacl.planNodes;

import org.eclipse.rdf4j.IsolationLevels;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.common.iteration.Iterations;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.impl.MapBindingSet;
import org.eclipse.rdf4j.query.parser.ParsedQuery;
import org.eclipse.rdf4j.query.parser.QueryParserFactory;
import org.eclipse.rdf4j.query.parser.QueryParserRegistry;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.sail.SailException;
import org.eclipse.rdf4j.sail.shacl.ShaclSailConnection;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.stream.Stream;

/**
 * @author Håvard Ottestad
 */
public class BulkedExternalLeftOuterJoin implements PlanNode {

	private IRI predicate;
	ShaclSailConnection shaclSailConnection;
	PlanNode parent;
	Repository repository;
	String query;

	public BulkedExternalLeftOuterJoin(PlanNode parent, Repository repository, String query) {
		this.parent = parent;
		this.repository = repository;
		this.query = query;
	}

	public BulkedExternalLeftOuterJoin(PlanNode parent, Repository repository, IRI predicate) {
		this.parent = parent;
		this.repository = repository;
		this.predicate = predicate;
	}

	public BulkedExternalLeftOuterJoin(PlanNode parent, ShaclSailConnection shaclSailConnection, String query) {
		this.parent = parent;
		this.query = query;

		this.shaclSailConnection = shaclSailConnection;

	}

	@Override
	public CloseableIteration<Tuple, SailException> iterator() {
		return new CloseableIteration<Tuple, SailException>() {

			LinkedList<Tuple> left = new LinkedList<>();

			LinkedList<Tuple> right = new LinkedList<>();

			CloseableIteration<Tuple, SailException> parentIterator = parent.iterator();


			private void calculateNext() {

				if (!left.isEmpty()) {
					return;
				}


				while (left.size() < 100 && parentIterator.hasNext()) {
					left.addFirst(parentIterator.next());
				}


				if (left.isEmpty()) {
					return;
				}
				if (query != null) {

					StringBuilder newQuery = new StringBuilder("select * where { VALUES (?a) { \n");

					left.stream().map(tuple -> tuple.line.get(0)).map(v -> (Resource) v).forEach(r -> newQuery.append("( <").append(r.toString()).append("> )\n"));

					newQuery.append("\n}")
						.append(query)
						.append("} order by ?a");

					if (repository != null) {
						try (RepositoryConnection connection = repository.getConnection()) {
							connection.begin(IsolationLevels.NONE);

							try (Stream<BindingSet> stream = Iterations.stream(connection.prepareTupleQuery(newQuery.toString()).evaluate())) {
								stream.map(Tuple::new).forEach(right::addFirst);
							}
							connection.commit();
						}
					} else {

						QueryParserFactory queryParserFactory = QueryParserRegistry.getInstance().get(QueryLanguage.SPARQL).get();

						ParsedQuery parsedQuery = queryParserFactory.getParser().parseQuery(newQuery.toString(), null);

						try (CloseableIteration<? extends BindingSet, QueryEvaluationException> evaluate = shaclSailConnection.evaluate(parsedQuery.getTupleExpr(), parsedQuery.getDataset(), new MapBindingSet(), true)) {
							while (evaluate.hasNext()) {
								BindingSet next = evaluate.next();
								right.addFirst(new Tuple(next));
							}
						}

					}
				} else {
					try (RepositoryConnection connection = repository.getConnection()) {
						connection.begin(IsolationLevels.NONE);

						for (Tuple tuple : left) {
							try (Stream<Statement> stream = Iterations.stream(connection.getStatements((Resource) tuple.line.get(0), predicate, null))) {
								stream.forEach(next -> right.addFirst(new Tuple(Arrays.asList(next.getSubject(), next.getObject()))));
							}
						}

						connection.commit();
					}
				}

			}

			@Override
			public void close() throws SailException {
				parentIterator.close();
			}

			@Override
			public boolean hasNext() throws SailException {
				calculateNext();
				return !left.isEmpty();
			}


			@Override
			public Tuple next() throws SailException {
				calculateNext();

				if (!left.isEmpty()) {

					Tuple leftPeek = left.peekLast();

					Tuple joined = null;

					if (!right.isEmpty()) {
						Tuple rightPeek = right.peekLast();

						if (rightPeek.line.get(0) == leftPeek.line.get(0) || rightPeek.line.get(0).equals(leftPeek.line.get(0))) {
							// we have a join !
							joined = TupleHelper.join(leftPeek, rightPeek);
							right.removeLast();

							Tuple rightPeek2 = right.peekLast();

							if (rightPeek2 == null || !rightPeek2.line.get(0).equals(leftPeek.line.get(0))) {
								// no more to join from right, pop left so we don't print it again.

								left.removeLast();
							}


						}

					}


					if (joined != null) {
						return joined;
					} else {
						left.removeLast();
						return leftPeek;
					}


				}


				return null;
			}

			@Override
			public void remove() throws SailException {

			}
		};
	}

	@Override
	public int depth() {
		return parent.depth() + 1;
	}
}
