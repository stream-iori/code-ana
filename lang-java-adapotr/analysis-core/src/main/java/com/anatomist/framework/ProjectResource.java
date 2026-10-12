package com.anatomist.framework;

import java.nio.file.Path;

/** A project resource shared by every matching project analyzer. */
public record ProjectResource(Path path, String sourceFile, String kind) {}
