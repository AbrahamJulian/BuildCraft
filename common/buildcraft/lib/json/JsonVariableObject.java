package buildcraft.lib.json;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

import net.minecraft.util.JsonUtils;

import buildcraft.lib.expression.FunctionContext;
import buildcraft.lib.expression.InternalCompiler;
import buildcraft.lib.expression.api.IExpressionNode;
import buildcraft.lib.expression.api.InvalidExpressionException;
import buildcraft.lib.expression.api.NodeType;
import buildcraft.lib.expression.node.value.ITickableNode;
import buildcraft.lib.expression.node.value.NodeStateful;
import buildcraft.lib.expression.node.value.NodeStateful.IGetterFunc;
import buildcraft.lib.expression.node.value.NodeUpdatable;

public class JsonVariableObject {

    public Map<String, ITickableNode.Source> variables = new LinkedHashMap<>();
    private ITickableNode.Source[] variablesArray;

    protected void putVariables(JsonObject values, FunctionContext fnCtx) {
        for (Entry<String, JsonElement> entry : values.entrySet()) {
            String name = entry.getKey();
            name = name.toLowerCase(Locale.ROOT);
            if (fnCtx.hasLocalVariable(name)) {
                throw new JsonSyntaxException("Duplicate local variable '" + name + "'");
            } else if (fnCtx.getVariable(name) != null) {
                // Allow overriding of higher up variables
                // ...what? Doesn't this disallow overriding existing variables?
                continue;
            }
            JsonElement value = entry.getValue();
            String type = null, getter = null, rounder = null;

            if (value.isJsonObject()) {
                JsonObject objValue = value.getAsJsonObject();
                value = objValue.get("value");
                type = JsonUtils.getString(objValue, "type");
                getter = JsonUtils.getString(objValue, "getter");
                if (objValue.has("rounder")) {
                    rounder = JsonUtils.getString(objValue, "rounder");
                }
            }

            if (!value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                throw new JsonSyntaxException("Expected a string, got " + value + " for the variable '" + name + "'");
            }
            NodeStateful stateful = null;
            if (getter != null) {
                // stateful node
                NodeType nodeType;
                try {
                    nodeType = NodeType.parseType(type);
                } catch (InvalidExpressionException iee) {
                    throw new JsonSyntaxException("Could not parse node type for variable '" + name + "'", iee);
                }
                IGetterFunc getterFunc = parseGetterFunction(getter, fnCtx);
                try {
                    stateful = new NodeStateful(name, nodeType, getterFunc);
                } catch (InvalidExpressionException iee) {
                    throw new JsonSyntaxException("Could not create a getter for the variable '" + name + "'", iee);
                }
                fnCtx.putVariable(name, stateful.getter);
                if (rounder != null) {
                    FunctionContext fnCtx2 = new FunctionContext(fnCtx);
                    fnCtx2.putVariable("last", stateful.last);
                    fnCtx2.putVariable("var", stateful.variable);
                    fnCtx2.putVariable("value", stateful.rounderValue);
                    try {
                        IExpressionNode nodeRounder = InternalCompiler.compileExpression(rounder, fnCtx2);
                        stateful.setRounder(nodeRounder);
                    } catch (InvalidExpressionException iee) {
                        throw new JsonSyntaxException("Could not compile a rounder for the variable '" + name + "'",
                            iee);
                    }
                }
            }

            String expression = value.getAsString();
            IExpressionNode node;
            try {
                node = InternalCompiler.compileExpression(expression, fnCtx);
            } catch (InvalidExpressionException e) {
                throw new JsonSyntaxException("Invalid expression " + expression, e);
            }
            if (variables.containsKey(name)) {
                ITickableNode.Source existing = variables.get(name);
                existing.setSource(node);
            } else if (stateful != null) {
                stateful.setSource(node);
                variables.put(name, stateful);
            } else {
                NodeUpdatable nodeUpdatable = new NodeUpdatable(name, node);
                variables.put(name, nodeUpdatable);
                fnCtx.putVariable(name, nodeUpdatable.variable);
            }
        }
    }

    private static IGetterFunc parseGetterFunction(String getter, FunctionContext fnCtx) {
        if ("interpolate_partial_ticks".equalsIgnoreCase(getter)) {
            return NodeStateful.GetterType.INTERPOLATE_PARTIAL_TICKS;
        }
        if ("last".equalsIgnoreCase(getter)) {
            return NodeStateful.GetterType.USE_LAST;
        }
        if ("var".equalsIgnoreCase(getter)) {
            return NodeStateful.GetterType.USE_VAR;
        }
        return (var, last) -> {
            FunctionContext fnCtx2 = new FunctionContext(fnCtx);
            fnCtx2.putVariable("var", var);
            fnCtx2.putVariable("last", last);
            return InternalCompiler.compileExpression(getter, fnCtx2);
        };
    }

    protected void finaliseVariables() {
        variablesArray = variables.values().toArray(new ITickableNode.Source[0]);
    }

    public ITickableNode[] createTickableNodes() {
        ITickableNode[] nodes = new ITickableNode[variablesArray.length];
        for (int i = 0; i < nodes.length; i++) {
            nodes[i] = variablesArray[i].createTickable();
        }
        return nodes;
    }
}
