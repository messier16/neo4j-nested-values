/*
 * Copyright (c) 2002-2018 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
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
package org.neo4j.kernel.impl.index.schema.fusion;

import java.util.Arrays;

import org.neo4j.collection.primitive.PrimitiveLongResourceIterator;
import org.neo4j.graphdb.Resource;
import org.neo4j.internal.kernel.api.IndexOrder;
import org.neo4j.internal.kernel.api.IndexQuery;
import org.neo4j.internal.kernel.api.IndexQuery.ExistsPredicate;
import org.neo4j.kernel.api.exceptions.index.IndexNotApplicableKernelException;
import org.neo4j.kernel.api.schema.index.SchemaIndexDescriptor;
import org.neo4j.kernel.impl.api.schema.BridgingIndexProgressor;
import org.neo4j.storageengine.api.schema.IndexProgressor;
import org.neo4j.storageengine.api.schema.IndexReader;
import org.neo4j.storageengine.api.schema.IndexSampler;
import org.neo4j.values.storable.Value;

import static java.lang.String.format;
import static org.neo4j.collection.primitive.PrimitiveLongResourceCollections.concat;
import static org.neo4j.kernel.impl.index.schema.fusion.SlotSelector.INSTANCE_COUNT;
import static org.neo4j.kernel.impl.index.schema.fusion.SlotSelector.UNKNOWN;

class FusionIndexReader extends FusionIndexBase<IndexReader> implements IndexReader
{
    private final SchemaIndexDescriptor descriptor;

    FusionIndexReader( SlotSelector slotSelector, Selector<IndexReader> selector, SchemaIndexDescriptor descriptor )
    {
        super( slotSelector, selector );
        this.descriptor = descriptor;
    }

    @Override
    public void close()
    {
        forInstantiated( Resource::close, selector );
    }

    @Override
    public long countIndexedNodes( long nodeId, Value... propertyValues )
    {
        return selector.select( slotSelector.selectSlot( propertyValues, GROUP_OF ) ).countIndexedNodes( nodeId, propertyValues );
    }

    @Override
    public IndexSampler createSampler()
    {
        return new FusionIndexSampler( instancesAs( new IndexSampler[INSTANCE_COUNT], IndexReader::createSampler ) );
    }

    @Override
    public PrimitiveLongResourceIterator query( IndexQuery... predicates ) throws IndexNotApplicableKernelException
    {
        int slot = slotSelector.selectSlot( predicates, IndexQuery::valueGroup );
        return slot != UNKNOWN ? selector.select( slot ).query( predicates )
                               : concat( instancesAs( new PrimitiveLongResourceIterator[INSTANCE_COUNT], reader -> reader.query( predicates ) ) );
    }

    @Override
    public void query( IndexProgressor.NodeValueClient cursor, IndexOrder indexOrder, IndexQuery... predicates )
            throws IndexNotApplicableKernelException
    {
        int slot = slotSelector.selectSlot( predicates, IndexQuery::valueGroup );
        if ( slot != UNKNOWN )
        {
            selector.select( slot ).query( cursor, indexOrder, predicates );
        }
        else
        {
            if ( indexOrder != IndexOrder.NONE )
            {
                throw new UnsupportedOperationException(
                        format( "Tried to query index with unsupported order %s. Supported orders for query %s are %s.",
                                indexOrder, Arrays.toString( predicates ), IndexOrder.NONE ) );
            }
            BridgingIndexProgressor multiProgressor = new BridgingIndexProgressor( cursor,
                    descriptor.schema().getPropertyIds() );
            cursor.initialize( descriptor, multiProgressor, predicates );
            forAll( reader -> reader.query( multiProgressor, indexOrder, predicates ), selector );
        }
    }

    @Override
    public boolean hasFullValuePrecision( IndexQuery... predicates )
    {
        int slot = slotSelector.selectSlot( predicates, IndexQuery::valueGroup );
        if ( slot != UNKNOWN )
        {
            return selector.select( slot ).hasFullValuePrecision( predicates );
        }
        else
        {
            if ( !(predicates.length == 1 && predicates[0] instanceof ExistsPredicate) )
            {
                throw new IllegalStateException( "Selected IndexReader null for predicates " + Arrays.toString( predicates ) );
            }
            // null means ExistsPredicate and we don't care about
            // full value precision for that, therefor true.
            return true;
        }
    }
}
