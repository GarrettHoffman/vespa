// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchdefinition.expressiontransforms;

import com.yahoo.searchdefinition.FeatureNames;
import com.yahoo.searchlib.rankingexpression.Reference;
import com.yahoo.searchlib.rankingexpression.evaluation.TensorValue;
import com.yahoo.searchlib.rankingexpression.evaluation.Value;
import com.yahoo.searchlib.rankingexpression.rule.CompositeNode;
import com.yahoo.searchlib.rankingexpression.rule.ExpressionNode;
import com.yahoo.searchlib.rankingexpression.rule.ReferenceNode;
import com.yahoo.searchlib.rankingexpression.transform.ExpressionTransformer;

import java.util.ArrayList;
import java.util.List;

/**
 * Transforms named references to constant tensors with the rank feature 'constant'.
 *
 * @author geirst
 */
public class ConstantTensorTransformer extends ExpressionTransformer<RankProfileTransformContext> {

    @Override
    public ExpressionNode transform(ExpressionNode node, RankProfileTransformContext context) {
        if (node instanceof ReferenceNode) {
            return transformFeature((ReferenceNode) node, context);
        } else if (node instanceof CompositeNode) {
            return transformChildren((CompositeNode) node, context);
        } else {
            return node;
        }
    }

    private ExpressionNode transformFeature(ReferenceNode node, RankProfileTransformContext context) {
        if ( ! node.getArguments().isEmpty() && ! FeatureNames.isSimpleFeature(node.reference())) {
            return transformArguments(node, context);
        } else {
            return transformConstantReference(node, context);
        }
    }

    private ExpressionNode transformArguments(ReferenceNode node, RankProfileTransformContext context) {
        List<ExpressionNode> arguments = node.getArguments().expressions();
        List<ExpressionNode> transformedArguments = new ArrayList<>(arguments.size());
        for (ExpressionNode argument : arguments) {
            transformedArguments.add(transform(argument, context));
        }
        return node.setArguments(transformedArguments);
    }

    private ExpressionNode transformConstantReference(ReferenceNode node, RankProfileTransformContext context) {
        Reference constantReference = node.reference();
        if ( ! FeatureNames.isConstantFeature(constantReference) && constantReference.isIdentifier())
            constantReference = FeatureNames.asConstantFeature(node.getName());

        Value value = context.constants().get(node.getName());
        if (value == null || value.type().rank() == 0) return node;

        TensorValue tensorValue = (TensorValue)value;
        String tensorType = tensorValue.asTensor().type().toString();
        context.rankProperties().put(constantReference.toString() + ".value", tensorValue.toString());
        context.rankProperties().put(constantReference.toString() + ".type", tensorType);
        return new ReferenceNode(constantReference);
    }

}
