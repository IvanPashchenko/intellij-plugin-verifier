/*
 * Copyright 2000-2020 JetBrains s.r.o. and other contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package com.jetbrains.pluginverifier.results.problems

import com.jetbrains.plugin.structure.base.utils.formatMessage
import com.jetbrains.pluginverifier.results.hierarchy.ClassHierarchy
import com.jetbrains.pluginverifier.results.instruction.Instruction
import com.jetbrains.pluginverifier.results.location.MethodLocation
import com.jetbrains.pluginverifier.results.presentation.*
import com.jetbrains.pluginverifier.results.reference.FieldReference
import java.util.*

class FieldNotFoundProblem(
  val unresolvedField: FieldReference,
  val accessor: MethodLocation,
  val fieldOwnerHierarchy: ClassHierarchy,
  val instruction: Instruction
) : CompatibilityProblem() {

  override val problemType
    get() = "Field not found"

  override val shortDescription
    get() = "Access to unresolved field {0}".formatMessage(unresolvedField)

  private val descriptionMainPart
    get() = buildString {
      append(
        "Method {0} contains a *{1}* instruction referencing an unresolved field {2}. ".formatMessage(
          accessor.formatMethodLocation(
            HostClassOption.FULL_HOST_NAME,
            MethodParameterTypeOption.FULL_PARAM_CLASS_NAME,
            MethodReturnTypeOption.FULL_RETURN_TYPE_CLASS_NAME,
            MethodParameterNameOption.WITH_PARAM_NAMES_IF_AVAILABLE
          ),
          instruction,
          unresolvedField.formatFieldReference(HostClassOption.FULL_HOST_NAME, FieldTypeOption.FULL_TYPE)
        )
      )
      append("This can lead to **NoSuchFieldError** exception at runtime.")
    }

  override val fullDescription
    get() = buildString {
      append(descriptionMainPart)
      //Instance fields can only be declared in super classes.
      val canBeDeclaredInSuperInterface = instruction == Instruction.GET_STATIC || instruction == Instruction.PUT_STATIC
      val hint = HierarchicalProblemsDescription.presentableElementMightHaveBeenDeclaredInSuperTypes("field", fieldOwnerHierarchy, true, canBeDeclaredInSuperInterface)
      if (hint.isNotEmpty()) {
        appendLine()
        append(hint)
      }
    }

  override fun equals(other: Any?) = other is FieldNotFoundProblem
    && unresolvedField == other.unresolvedField
    && accessor == other.accessor
    && instruction == other.instruction

  override fun hashCode() = Objects.hash(unresolvedField, accessor, instruction)
}