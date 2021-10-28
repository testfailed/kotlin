/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.impl.base.test.scopes

import org.jetbrains.kotlin.analysis.api.KtAnalysisSession
import org.jetbrains.kotlin.analysis.api.impl.barebone.test.FrontendApiTestConfiguratorService
import org.jetbrains.kotlin.analysis.api.impl.barebone.test.expressionMarkerProvider
import org.jetbrains.kotlin.analysis.api.impl.base.test.symbols.AbstractSymbolByFqNameTest
import org.jetbrains.kotlin.analysis.api.symbols.KtClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KtSymbolWithMembers
import org.jetbrains.kotlin.psi.KtClassLikeDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.test.services.TestServices

/**
 * Sometimes it is impossible to specify a declaration by a FQN like in [AbstractMemberScopeByFqNameTest]; this
 * test base allows to specify it by the `<caret>` in the code.
 */
abstract class AbstractMemberScopeByCaretTest(
    configurator: FrontendApiTestConfiguratorService
) : AbstractSymbolByFqNameTest(configurator) {

    override fun KtAnalysisSession.collectSymbols(ktFile: KtFile, testServices: TestServices): List<KtSymbol> {
        val psiDeclaration = testServices.expressionMarkerProvider.getElementOfTypAtCaret<KtClassLikeDeclaration>(ktFile)
        val classSymbol = psiDeclaration.getSymbol() as KtClassLikeSymbol

        require(classSymbol is KtSymbolWithMembers)

        return classSymbol.getMemberScope().getAllSymbols().toList()
    }
}