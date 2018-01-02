/*
 * Copyright (c) 2002-2018 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.v2_3.planner.logical.greedy

import org.neo4j.cypher.internal.compiler.v2_3.planner.QueryGraph
import org.neo4j.cypher.internal.compiler.v2_3.planner.logical.plans.{LogicalPlan, PatternRelationship}
import org.neo4j.cypher.internal.compiler.v2_3.planner.logical.{CandidateGenerator, LogicalPlanningContext, PlanTransformer}

object projectEndpoints extends CandidateGenerator[GreedyPlanTable] {

  def apply(planTable: GreedyPlanTable, queryGraph: QueryGraph)(implicit context: LogicalPlanningContext): Seq[LogicalPlan] = {
    for {
      plan <- planTable.plans
      patternRel <- queryGraph.patternRelationships
      if canProjectPatternRelationshipEndpoints(plan, patternRel)
    } yield {
      doPlan(plan, patternRel)
    }
  }

  private def doPlan(plan: LogicalPlan, patternRel: PatternRelationship)(implicit context: LogicalPlanningContext): LogicalPlan = {
    val (start, end) = patternRel.inOrder
    val isStartInScope = plan.availableSymbols(start)
    val isEndInScope = plan.availableSymbols(end)
    context.logicalPlanProducer.planEndpointProjection(plan, start, isStartInScope, end, isEndInScope, patternRel)
  }


  private def canProjectPatternRelationshipEndpoints(plan: LogicalPlan, patternRel: PatternRelationship) = {
    val inScope = plan.availableSymbols(patternRel.name)
    val solved = plan.solved.graph.patternRelationships(patternRel)
    inScope && !solved
  }

  object all extends PlanTransformer[QueryGraph] {
    override def apply(plan: LogicalPlan, qg: QueryGraph)(implicit context: LogicalPlanningContext): LogicalPlan =
      qg.patternRelationships.foldLeft(plan) { (accPlan, patternRel) =>
        if (canProjectPatternRelationshipEndpoints(accPlan, patternRel)) {
          doPlan(accPlan, patternRel)
        } else {
          accPlan
        }
      }
  }
}
