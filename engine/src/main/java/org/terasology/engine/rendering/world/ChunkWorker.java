// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.engine.rendering.world;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector3i;
import org.joml.Vector3ic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.engine.core.GameScheduler;
import org.terasology.engine.monitoring.chunk.ChunkMonitor;
import org.terasology.engine.rendering.primitives.ChunkMesh;
import org.terasology.engine.rendering.primitives.ChunkTessellator;
import org.terasology.engine.rendering.world.viewDistance.ViewDistance;
import org.terasology.engine.world.ChunkView;
import org.terasology.engine.world.WorldProvider;
import org.terasology.engine.world.chunks.Chunk;
import org.terasology.engine.world.chunks.Chunks;
import reactor.core.publisher.Sinks;
import reactor.function.TupleUtils;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class ChunkWorker {
    private static final Logger logger = LoggerFactory.getLogger(ChunkWorker.class);

    private static final int MAX_LOADABLE_CHUNKS =
            ViewDistance.MEGA.getChunkDistance().x() * ViewDistance.MEGA.getChunkDistance().y() * ViewDistance.MEGA.getChunkDistance().z();

    private final RenderableWorldImpl.ChunkFrontToBackComparator frontToBackComparator;
    private final Set<Vector3ic> chunkMeshProcessing = Sets.newConcurrentHashSet();
    private final Sinks.Many<Chunk> chunkMeshPublisher = Sinks.many().unicast().onBackpressureBuffer();
    private final List<Chunk> chunksInProximityOfCamera = Lists.newArrayListWithCapacity(MAX_LOADABLE_CHUNKS);

    public ChunkWorker(ChunkTessellator chunkTessellator,
                       WorldProvider worldProvider,
                       RenderableWorldImpl.ChunkFrontToBackComparator frontToBackComparator) {
        this.frontToBackComparator = frontToBackComparator;

        chunkMeshPublisher.asFlux()
                .distinct(Chunk::getPosition, () -> chunkMeshProcessing)
                .doOnNext(k -> k.setDirty(false))
                .parallel(5).runOn(GameScheduler.parallel())
                .<Optional<Tuple2<Chunk, ChunkMesh>>>map(c -> {
                    ChunkView chunkView = worldProvider.getLocalView(c.getPosition());
                    if (chunkView != null && chunkView.isValidView() && chunkMeshProcessing.remove(c.getPosition())) {
                        ChunkMesh newMesh = chunkTessellator.generateMesh(chunkView);
                        ChunkMonitor.fireChunkTessellated(new Vector3i(c.getPosition()), newMesh);
                        return Optional.of(Tuples.of(c, newMesh));
                    }
                    return Optional.empty();
                }).filter(Optional::isPresent).sequential()
                .publishOn(GameScheduler.gameMain())
                .subscribe(result -> result.ifPresent(TupleUtils.consumer((chunk, chunkMesh) -> {
                    if (chunksInProximityOfCamera.contains(chunk)) {
                        chunkMesh.updateMesh();
                        chunkMesh.discardData();
                        if (chunk.hasMesh()) {
                            chunk.getMesh().dispose();
                        }
                        chunk.setMesh(chunkMesh);
                    }

                })), throwable -> logger.error("Failed to build mesh {}", throwable));
    }


    public void add(Chunk chunk) {
        if (chunk != null) {
            chunksInProximityOfCamera.add(chunk);
        }
    }

    public void remove(Chunk chunk) {
        chunkMeshProcessing.remove(chunk.getPosition());

        chunksInProximityOfCamera.remove(chunk);
        chunk.disposeMesh();
    }

    public void remove(Vector3ic coord) {
        chunkMeshProcessing.remove(coord);

        Iterator<Chunk> iterator = chunksInProximityOfCamera.iterator();
        while (iterator.hasNext()) {
            Chunk chunk = iterator.next();
            if (chunk.getPosition().equals(coord)) {
                chunk.disposeMesh();
                iterator.remove();
                break;
            }
        }
    }


    public int update() {
        int statDirtyChunks = 0;
        chunksInProximityOfCamera.sort(frontToBackComparator);
        for (Chunk chunk : chunksInProximityOfCamera) {
            if (chunk.isReady() && chunk.isDirty()) {
                statDirtyChunks++;
                Sinks.EmitResult result = chunkMeshPublisher.tryEmitNext(chunk);
                if (result.isFailure()) {
                    logger.error("failed to process chunk {} : {}", chunk, result);
                }
            }
        }
        return statDirtyChunks;
    }

    public int numberChunkMeshProcessing() {
        return chunkMeshProcessing.size();
    }

    public Collection<Chunk> chunks() {
        return chunksInProximityOfCamera;
    }
}
