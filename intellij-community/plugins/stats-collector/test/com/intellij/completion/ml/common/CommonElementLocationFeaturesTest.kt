// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.completion.ml.common

import com.intellij.codeInsight.completion.CompletionLocation
import com.intellij.codeInsight.completion.LightCompletionTestCase
import com.intellij.codeInsight.completion.ml.ContextFeatures
import com.intellij.codeInsight.completion.ml.ElementFeatureProvider
import com.intellij.codeInsight.completion.ml.MLFeatureValue
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.lang.Language
import com.intellij.psi.PsiNamedElement
import junit.framework.TestCase

class CommonElementLocationFeaturesTest: LightCompletionTestCase() {

  fun `test lines diff`() {
    val features = calculateFeature("lines_diff", "Test.java") {
      """|class Test {
         |  private int a = 1;
         |  private double b = 2;
         |  
         |  void f() {
         |    long c = 3;
         |    System.out.println(<caret>);
         |  }
         |  
         |  char getChar() {
         |    return 'a';
         |  }
         |  
         |  static class Inner {
         |  }
         |}
      """.trimMargin()
    }

    assertFeaturesEquals(features.getValue("Test"), MLFeatureValue.float(6))
    assertFeaturesEquals(features.getValue("a"), MLFeatureValue.float(5))
    assertFeaturesEquals(features.getValue("b"), MLFeatureValue.float(4))
    assertFeaturesEquals(features.getValue("f"), MLFeatureValue.float(2))
    assertFeaturesEquals(features.getValue("c"), MLFeatureValue.float(1))
    assertFeaturesEquals(features.getValue("getChar"), MLFeatureValue.float(-3))
    assertFeaturesEquals(features.getValue("Inner"), MLFeatureValue.float(-7))
    assertFeaturesEquals(features.getValue("Exception"), MLFeatureValue.float(Int.MIN_VALUE))
  }

  @Suppress("SameParameterValue")
  private fun calculateFeature(featureName: String, fileName: String, text: () -> String): Map<String, MLFeatureValue> {
    val result: MutableMap<String, MLFeatureValue> = mutableMapOf()

    val provider = object: ElementFeatureProvider {
      private val original = CommonElementLocationFeatures()

      override fun getName(): String = original.name
      override fun calculateFeatures(element: LookupElement,
                                     location: CompletionLocation,
                                     contextFeatures: ContextFeatures): MutableMap<String, MLFeatureValue> {
        val features = original.calculateFeatures(element, location, contextFeatures)

        when (val namedElement = element.psiElement) {
          is PsiNamedElement -> namedElement.name?.let { result[it] = features[featureName]!! }
          else -> {}
        }

        return features
      }
    }

    try {
      ElementFeatureProvider.EP_NAME.addExplicitExtension(Language.ANY, provider)
      configureFromFileText(fileName, text())
      complete()
      return result
    }
    finally {
      ElementFeatureProvider.EP_NAME.removeExplicitExtension(Language.ANY, provider)
    }
  }

  private fun assertFeaturesEquals(expected: MLFeatureValue, actual: MLFeatureValue) {
    TestCase.assertEquals(expected.toString(), actual.toString())
  }
}