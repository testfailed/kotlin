/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.parcelize.test.runners;

import com.intellij.testFramework.TestDataPath;
import org.jetbrains.kotlin.test.util.KtTestUtil;
import org.jetbrains.kotlin.test.TestMetadata;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.regex.Pattern;

/** This class is generated by {@link GenerateNewCompilerTests.kt}. DO NOT MODIFY MANUALLY */
@SuppressWarnings("all")
@TestMetadata("plugins/parcelize/parcelize-compiler/testData/diagnostics")
@TestDataPath("$PROJECT_ROOT")
public class ParcelizeDiagnosticTestGenerated extends AbstractParcelizeDiagnosticTest {
    @Test
    public void testAllFilesPresentInDiagnostics() throws Exception {
        KtTestUtil.assertAllTestsPresentByMetadataWithExcluded(this.getClass(), new File("plugins/parcelize/parcelize-compiler/testData/diagnostics"), Pattern.compile("^(.+)\\.kt$"), Pattern.compile("^(.+)\\.fir\\.kts?$"), true);
    }

    @Test
    @TestMetadata("constructors.kt")
    public void testConstructors() throws Exception {
        runTest("plugins/parcelize/parcelize-compiler/testData/diagnostics/constructors.kt");
    }

    @Test
    @TestMetadata("customCreator.kt")
    public void testCustomCreator() throws Exception {
        runTest("plugins/parcelize/parcelize-compiler/testData/diagnostics/customCreator.kt");
    }

    @Test
    @TestMetadata("customParcelers.kt")
    public void testCustomParcelers() throws Exception {
        runTest("plugins/parcelize/parcelize-compiler/testData/diagnostics/customParcelers.kt");
    }

    @Test
    @TestMetadata("customWriteToParcel.kt")
    public void testCustomWriteToParcel() throws Exception {
        runTest("plugins/parcelize/parcelize-compiler/testData/diagnostics/customWriteToParcel.kt");
    }

    @Test
    @TestMetadata("delegate.kt")
    public void testDelegate() throws Exception {
        runTest("plugins/parcelize/parcelize-compiler/testData/diagnostics/delegate.kt");
    }

    @Test
    @TestMetadata("deprecatedAnnotations.kt")
    public void testDeprecatedAnnotations() throws Exception {
        runTest("plugins/parcelize/parcelize-compiler/testData/diagnostics/deprecatedAnnotations.kt");
    }

    @Test
    @TestMetadata("emptyPrimaryConstructor.kt")
    public void testEmptyPrimaryConstructor() throws Exception {
        runTest("plugins/parcelize/parcelize-compiler/testData/diagnostics/emptyPrimaryConstructor.kt");
    }

    @Test
    @TestMetadata("kt20062.kt")
    public void testKt20062() throws Exception {
        runTest("plugins/parcelize/parcelize-compiler/testData/diagnostics/kt20062.kt");
    }

    @Test
    @TestMetadata("modality.kt")
    public void testModality() throws Exception {
        runTest("plugins/parcelize/parcelize-compiler/testData/diagnostics/modality.kt");
    }

    @Test
    @TestMetadata("notMagicParcel.kt")
    public void testNotMagicParcel() throws Exception {
        runTest("plugins/parcelize/parcelize-compiler/testData/diagnostics/notMagicParcel.kt");
    }

    @Test
    @TestMetadata("properties.kt")
    public void testProperties() throws Exception {
        runTest("plugins/parcelize/parcelize-compiler/testData/diagnostics/properties.kt");
    }

    @Test
    @TestMetadata("simple.kt")
    public void testSimple() throws Exception {
        runTest("plugins/parcelize/parcelize-compiler/testData/diagnostics/simple.kt");
    }

    @Test
    @TestMetadata("unsupportedType.kt")
    public void testUnsupportedType() throws Exception {
        runTest("plugins/parcelize/parcelize-compiler/testData/diagnostics/unsupportedType.kt");
    }

    @Test
    @TestMetadata("withoutParcelableSupertype.kt")
    public void testWithoutParcelableSupertype() throws Exception {
        runTest("plugins/parcelize/parcelize-compiler/testData/diagnostics/withoutParcelableSupertype.kt");
    }

    @Test
    @TestMetadata("wrongAnnotationTarget.kt")
    public void testWrongAnnotationTarget() throws Exception {
        runTest("plugins/parcelize/parcelize-compiler/testData/diagnostics/wrongAnnotationTarget.kt");
    }
}
