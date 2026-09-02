package com.androidplay.mdclient.document;

import com.androidplay.mdclient.core.DocumentBlock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DocumentModel {
    private final List<DocumentBlock> blocks;
    private final long revision;

    public DocumentModel() { this(Collections.<DocumentBlock>emptyList(), 0L); }
    public DocumentModel(List<DocumentBlock> blocks, long revision) {
        this.blocks = Collections.unmodifiableList(new ArrayList<DocumentBlock>(blocks));
        this.revision = revision;
    }
    public List<DocumentBlock> getBlocks() { return blocks; }
    public long getRevision() { return revision; }
    public DocumentBlock find(String id) {
        for (DocumentBlock block : blocks) if (block.id.equals(id)) return block;
        return null;
    }
    public DocumentModel withBlocks(List<DocumentBlock> value) { return new DocumentModel(value, revision + 1); }
}
