package butterknife.compiler;

import android.support.annotation.LayoutRes;

import com.google.common.collect.ImmutableList;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.WildcardTypeName;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

import butterknife.internal.ListenerClass;
import butterknife.internal.ListenerMethod;

import static butterknife.compiler.ButterKnifeProcessor.ACTIVITY_TYPE;
import static butterknife.compiler.ButterKnifeProcessor.VIEW_TYPE;
import static butterknife.compiler.ButterKnifeProcessor.isSubtypeOfType;
import static com.google.auto.common.MoreElements.getPackage;
import static java.util.Collections.singletonList;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.PUBLIC;

/**
 * A set of all the bindings requested by a single type.
 */
final class BindingSet {
    static final ClassName UTILS = ClassName.get("butterknife.internal", "Utils");
    private static final ClassName VIEW = ClassName.get("android.view", "View");
    private static final ClassName LAYOUTINFLATER = ClassName.get("android.view", "LayoutInflater");
    private static final ClassName VIEWGROUP = ClassName.get("android.view", "ViewGroup");
    private static final ClassName CONTEXT = ClassName.get("android.content", "Context");
    private static final ClassName RESOURCES = ClassName.get("android.content.res", "Resources");
    private static final ClassName UI_THREAD =
            ClassName.get("android.support.annotation", "UiThread");
    private static final ClassName CALL_SUPER =
            ClassName.get("android.support.annotation", "CallSuper");
    private static final ClassName SUPPRESS_LINT =
            ClassName.get("android.annotation", "SuppressLint");
    private static final ClassName UNBINDER = ClassName.get("butterknife", "Unbinder");
    static final ClassName BITMAP_FACTORY = ClassName.get("android.graphics", "BitmapFactory");
    static final ClassName CONTEXT_COMPAT =
            ClassName.get("android.support.v4.content", "ContextCompat");

    private final TypeName targetTypeName;
    private final ClassName bindingClassName;
    private final boolean isFinal;
    private final boolean isActivity;
    private final ImmutableList<ViewBinding> viewBindings;
    private final ImmutableList<FieldCollectionViewBinding> collectionBindings;
    private final BindingSet parentBinding;
    private int layoutId;

    private BindingSet(TypeName targetTypeName, ClassName bindingClassName, boolean isFinal,
                       boolean isActivity, ImmutableList<ViewBinding> viewBindings,
                       ImmutableList<FieldCollectionViewBinding> collectionBindings,
                       BindingSet parentBinding, @LayoutRes int layoutId) {
        this.isFinal = isFinal;
        this.targetTypeName = targetTypeName;
        this.bindingClassName = bindingClassName;
        this.isActivity = isActivity;
        this.viewBindings = viewBindings;
        this.collectionBindings = collectionBindings;
        this.parentBinding = parentBinding;
        this.layoutId = layoutId;
    }

    JavaFile brewJava(int sdk) {
        return JavaFile.builder(bindingClassName.packageName(), createType(sdk))
                .addFileComment("Generated code from Butter Knife. Do not modify!")
                .build();
    }

    private TypeSpec createType(int sdk) {
        TypeSpec.Builder result = TypeSpec.classBuilder(bindingClassName.simpleName())
                .addModifiers(PUBLIC);
        if (isFinal) {
            result.addModifiers(FINAL);
        }
        if (parentBinding != null) {
            result.superclass(parentBinding.bindingClassName);
        } else {
            result.addSuperinterface(UNBINDER);
        }
        if (hasTargetField()) {
            result.addField(targetTypeName, "target", PRIVATE);
        }
        if (!isActivity) {
            result.addField(VIEW, "source", PRIVATE);
            result.addMethod(createBindingConstructorForView());
        }
        result.addMethod(createBindingConstructor(sdk));
        result.addMethod(createBindingUnbindMethod(result));
        result.addMethod(createGetLayoutMethod());
        return result.build();
    }
    private MethodSpec createBindingConstructorForView() {
        MethodSpec.Builder builder = MethodSpec.constructorBuilder()
                .addAnnotation(UI_THREAD)
                .addModifiers(PUBLIC)
                .addParameter(targetTypeName, "target",FINAL)
                .addParameter(VIEW, "source");
        builder.addStatement("this.target = target");
        builder.addCode("\n");
        builder.addStatement("this.source = source");
        builder.addCode("\n");
        if (hasViewBindings()) {
            if (hasViewLocal()) {
                builder.addStatement("$T view", VIEW);
            }
            for (ViewBinding binding : viewBindings) {
                addViewBinding(builder, binding);
            }
            for (FieldCollectionViewBinding binding : collectionBindings) {
                builder.addStatement("$L", binding.render());
            }
        }
        return builder.build();
    }
    private MethodSpec createBindingConstructor(int sdk) {
        MethodSpec.Builder constructor = MethodSpec.constructorBuilder()
                .addAnnotation(UI_THREAD)
                .addModifiers(PUBLIC);
        if (hasMethodBindings()) {
            constructor.addParameter(targetTypeName, "target", FINAL);
        } else {
            constructor.addParameter(targetTypeName, "target");
        }

        if(!isActivity){
            constructor.addParameter(LAYOUTINFLATER, "inflater");
            constructor.addParameter(VIEWGROUP, "container");
            constructor.addParameter(TypeName.INT, "layoutId");
        }else {
            constructor.addParameter(VIEW, "source");
            constructor.addParameter(TypeName.INT, "layoutId");
        }

        if (parentBinding != null) {
            if(!isActivity){
                if(layoutId != 0)
                    constructor.addStatement("super(target, inflater, container, $L)",layoutId);
                else
                    constructor.addStatement("super(target, inflater, container, layoutId)");
            }else if (parentBinding.constructorNeedsView()) {
                if(layoutId != 0)
                    constructor.addStatement("super(target, source, $L)",layoutId);
                else
                    constructor.addStatement("super(target, source, layoutId)");
            } else if (constructorNeedsView()) {
                constructor.addStatement("super(target, source.getContext())");
            } else {
                constructor.addStatement("super(target, context)");
            }
            constructor.addCode("\n");
        }
        if (hasTargetField()) {
            constructor.addStatement("this.target = target");
            constructor.addCode("\n");
        }
        if (isActivity) {
            if(layoutId != 0) {
                if(parentBinding == null)
                    constructor.addStatement("target.setContentView($L)", layoutId);
            }
            else {
                constructor.addStatement("if(layoutId != 0)\ntarget.setContentView(layoutId)");
            }
        }else {
            if (layoutId != 0) {
                if(parentBinding == null)
                    constructor.addStatement("source = inflater.inflate($L, container, false)", layoutId);
                else
                    constructor.addStatement("source = (View)super.getLayout()");
            }
            else {
                constructor.addStatement("if(layoutId != 0)\nsource = inflater.inflate(layoutId, container, false)");
            }
        }
        constructor.addCode("\n");
        if (hasViewBindings()) {
            if (hasViewLocal()) {
                // Local variable in which all views will be temporarily stored.
                constructor.addStatement("$T view", VIEW);
            }
            for (ViewBinding binding : viewBindings) {
                addViewBinding(constructor, binding);
            }
            for (FieldCollectionViewBinding binding : collectionBindings) {
                constructor.addStatement("$L", binding.render());
            }
        }
        return constructor.build();
    }

    private MethodSpec createGetLayoutMethod() {
        MethodSpec.Builder result = MethodSpec.methodBuilder("getLayout")
                .returns(TypeName.OBJECT)
                .addAnnotation(Override.class)
                .addModifiers(PUBLIC);
        if (!isActivity) {
            result.addStatement("return source");
        } else {
            result.addStatement("throw new $T($S)", IllegalStateException.class,
                    "sorry,you can't call this way");
        }
        return result.build();
    }

    private MethodSpec createBindingUnbindMethod(TypeSpec.Builder bindingClass) {
        MethodSpec.Builder result = MethodSpec.methodBuilder("unbind")
                .addAnnotation(Override.class)
                .addModifiers(PUBLIC);
        if (!isFinal && parentBinding == null) {
            result.addAnnotation(CALL_SUPER);
        }

        if (hasTargetField()) {
            if (hasFieldBindings()) {
                result.addStatement("$T target = this.target", targetTypeName);
            }
            result.addStatement("if (target == null) throw new $T($S)", IllegalStateException.class,
                    "Bindings already cleared.");
            result.addStatement("$N = null", hasFieldBindings() ? "this.target" : "target");
            result.addCode("\n");
            for (ViewBinding binding : viewBindings) {
                if (binding.getFieldBinding() != null) {
                    result.addStatement("target.$L = null", binding.getFieldBinding().getName());
                }
            }
            for (FieldCollectionViewBinding binding : collectionBindings) {
                result.addStatement("target.$L = null", binding.name);
            }
        }

        if (hasMethodBindings()) {
            result.addCode("\n");
            for (ViewBinding binding : viewBindings) {
                addFieldAndUnbindStatement(bindingClass, result, binding);
            }
        }

        if (parentBinding != null) {
            result.addCode("\n");
            result.addStatement("super.unbind()");
        }
        return result.build();
    }

    private void addFieldAndUnbindStatement(TypeSpec.Builder result, MethodSpec.Builder unbindMethod,
                                            ViewBinding bindings) {
        // Only add fields to the binding if there are method bindings.
        Map<ListenerClass, Map<ListenerMethod, Set<MethodViewBinding>>> classMethodBindings =
                bindings.getMethodBindings();
        if (classMethodBindings.isEmpty()) {
            return;
        }

        String fieldName = bindings.isBoundToRoot() ? "viewSource" : "view" + bindings.getId().value;
        result.addField(VIEW, fieldName, PRIVATE);

        // We only need to emit the null check if there are zero required bindings.
        boolean needsNullChecked = bindings.getRequiredBindings().isEmpty();
        if (needsNullChecked) {
            unbindMethod.beginControlFlow("if ($N != null)", fieldName);
        }

        for (ListenerClass listenerClass : classMethodBindings.keySet()) {
            // We need to keep a reference to the listener
            // in case we need to unbind it via a remove method.
            boolean requiresRemoval = !"".equals(listenerClass.remover());
            String listenerField = "null";
            if (requiresRemoval) {
                TypeName listenerClassName = bestGuess(listenerClass.type());
                listenerField = fieldName + ((ClassName) listenerClassName).simpleName();
                result.addField(listenerClassName, listenerField, PRIVATE);
            }

            if (!VIEW_TYPE.equals(listenerClass.targetType())) {
                unbindMethod.addStatement("(($T) $N).$N($N)", bestGuess(listenerClass.targetType()),
                        fieldName, removerOrSetter(listenerClass, requiresRemoval), listenerField);
            } else {
                unbindMethod.addStatement("$N.$N($N)", fieldName,
                        removerOrSetter(listenerClass, requiresRemoval), listenerField);
            }

            if (requiresRemoval) {
                unbindMethod.addStatement("$N = null", listenerField);
            }
        }

        unbindMethod.addStatement("$N = null", fieldName);

        if (needsNullChecked) {
            unbindMethod.endControlFlow();
        }
    }

    private String removerOrSetter(ListenerClass listenerClass, boolean requiresRemoval) {
        return requiresRemoval
                ? listenerClass.remover()
                : listenerClass.setter();
    }

    private void addViewBinding(MethodSpec.Builder result, ViewBinding binding) {
        if (binding.isSingleFieldBinding()) {
            // Optimize the common case where there's a single binding directly to a field.

            FieldViewBinding fieldBinding = binding.getFieldBinding();
            int parentId = fieldBinding.getParentId();
            CodeBlock.Builder builder = CodeBlock.builder()
                    .add("target.$L = ", fieldBinding.getName());

            boolean requiresCast = requiresCast(fieldBinding.getType());
            if (!requiresCast && !fieldBinding.isRequired()) {
                if(parentId != 0)
                    builder.add("source.findViewById($L).findViewById($L)",parentId,binding.getId().code);
                else
                    builder.add("source.findViewById($L)", binding.getId().code);
            } else {
                builder.add("$T.find", UTILS);
                builder.add(fieldBinding.isRequired() ? "RequiredView" : "OptionalView");
                if (requiresCast) {
                    builder.add("AsType");
                }
                if(parentId != 0)
                    builder.add("(source.findViewById($L), $L",parentId, binding.getId().code);
                else
                    builder.add("(source, $L", binding.getId().code);
                if (fieldBinding.isRequired() || requiresCast) {
                    builder.add(", $S", asHumanDescription(singletonList(fieldBinding)));
                }
                if (requiresCast) {
                    builder.add(", $T.class", fieldBinding.getRawType());
                }
                builder.add(")");
            }
            result.addStatement("$L", builder.build());
            return;
        }

        List<MemberViewBinding> requiredBindings = binding.getRequiredBindings();
        if (requiredBindings.isEmpty()) {
            result.addStatement("view = source.findViewById($L)", binding.getId().code);
        } else if (!binding.isBoundToRoot()) {
            result.addStatement("view = $T.findRequiredView(source, $L, $S)", UTILS,
                    binding.getId().code, asHumanDescription(requiredBindings));
        }

        addFieldBinding(result, binding);
        addMethodBindings(result, binding);
    }

    private void addFieldBinding(MethodSpec.Builder result, ViewBinding binding) {
        FieldViewBinding fieldBinding = binding.getFieldBinding();
        if (fieldBinding != null) {
            if (requiresCast(fieldBinding.getType())) {
                result.addStatement("target.$L = $T.castView(view, $L, $S, $T.class)",
                        fieldBinding.getName(), UTILS, binding.getId().code,
                        asHumanDescription(singletonList(fieldBinding)), fieldBinding.getRawType());
            } else {
                result.addStatement("target.$L = view", fieldBinding.getName());
            }
        }
    }

    private void addMethodBindings(MethodSpec.Builder result, ViewBinding binding) {
        Map<ListenerClass, Map<ListenerMethod, Set<MethodViewBinding>>> classMethodBindings =
                binding.getMethodBindings();
        if (classMethodBindings.isEmpty()) {
            return;
        }

        // We only need to emit the null check if there are zero required bindings.
        boolean needsNullChecked = binding.getRequiredBindings().isEmpty();
        if (needsNullChecked) {
            result.beginControlFlow("if (view != null)");
        }

        // Add the view reference to the binding.
        String fieldName = "viewSource";
        String bindName = "source";
        if (!binding.isBoundToRoot()) {
            fieldName = "view" + binding.getId().value;
            bindName = "view";
        }
        result.addStatement("$L = $N", fieldName, bindName);

        for (Map.Entry<ListenerClass, Map<ListenerMethod, Set<MethodViewBinding>>> e
                : classMethodBindings.entrySet()) {
            ListenerClass listener = e.getKey();
            Map<ListenerMethod, Set<MethodViewBinding>> methodBindings = e.getValue();

            TypeSpec.Builder callback = TypeSpec.anonymousClassBuilder("")
                    .superclass(ClassName.bestGuess(listener.type()));

            for (ListenerMethod method : getListenerMethods(listener)) {
                MethodSpec.Builder callbackMethod = MethodSpec.methodBuilder(method.name())
                        .addAnnotation(Override.class)
                        .addModifiers(PUBLIC)
                        .returns(bestGuess(method.returnType()));
                String[] parameterTypes = method.parameters();
                for (int i = 0, count = parameterTypes.length; i < count; i++) {
                    callbackMethod.addParameter(bestGuess(parameterTypes[i]), "p" + i);
                }

                boolean hasReturnType = !"void".equals(method.returnType());
                CodeBlock.Builder builder = CodeBlock.builder();
                if (hasReturnType) {
                    builder.add("return ");
                }

                if (methodBindings.containsKey(method)) {
                    for (MethodViewBinding methodBinding : methodBindings.get(method)) {
                        builder.add("target.$L(", methodBinding.getName());
                        List<Parameter> parameters = methodBinding.getParameters();
                        String[] listenerParameters = method.parameters();
                        for (int i = 0, count = parameters.size(); i < count; i++) {
                            if (i > 0) {
                                builder.add(", ");
                            }

                            Parameter parameter = parameters.get(i);
                            int listenerPosition = parameter.getListenerPosition();

                            if (parameter.requiresCast(listenerParameters[listenerPosition])) {
                                builder.add("$T.<$T>castParam(p$L, $S, $L, $S, $L)", UTILS, parameter.getType(),
                                        listenerPosition, method.name(), listenerPosition, methodBinding.getName(), i);
                            } else {
                                builder.add("p$L", listenerPosition);
                            }
                        }
                        builder.add(");\n");
                    }
                } else if (hasReturnType) {
                    builder.add("$L;\n", method.defaultReturn());
                }
                callbackMethod.addCode(builder.build());
                callback.addMethod(callbackMethod.build());
            }

            boolean requiresRemoval = listener.remover().length() != 0;
            String listenerField = null;
            if (requiresRemoval) {
                TypeName listenerClassName = bestGuess(listener.type());
                listenerField = fieldName + ((ClassName) listenerClassName).simpleName();
                result.addStatement("$L = $L", listenerField, callback.build());
            }

            if (!VIEW_TYPE.equals(listener.targetType())) {
                result.addStatement("(($T) $N).$L($L)", bestGuess(listener.targetType()), bindName,
                        listener.setter(), requiresRemoval ? listenerField : callback.build());
            } else {
                result.addStatement("$N.$L($L)", bindName, listener.setter(),
                        requiresRemoval ? listenerField : callback.build());
            }
        }

        if (needsNullChecked) {
            result.endControlFlow();
        }
    }

    private static List<ListenerMethod> getListenerMethods(ListenerClass listener) {
        if (listener.method().length == 1) {
            return Arrays.asList(listener.method());
        }

        try {
            List<ListenerMethod> methods = new ArrayList<>();
            Class<? extends Enum<?>> callbacks = listener.callbacks();
            for (Enum<?> callbackMethod : callbacks.getEnumConstants()) {
                Field callbackField = callbacks.getField(callbackMethod.name());
                ListenerMethod method = callbackField.getAnnotation(ListenerMethod.class);
                if (method == null) {
                    throw new IllegalStateException(String.format("@%s's %s.%s missing @%s annotation.",
                            callbacks.getEnclosingClass().getSimpleName(), callbacks.getSimpleName(),
                            callbackMethod.name(), ListenerMethod.class.getSimpleName()));
                }
                methods.add(method);
            }
            return methods;
        } catch (NoSuchFieldException e) {
            throw new AssertionError(e);
        }
    }

    static String asHumanDescription(Collection<? extends MemberViewBinding> bindings) {
        Iterator<? extends MemberViewBinding> iterator = bindings.iterator();
        switch (bindings.size()) {
            case 1:
                return iterator.next().getDescription();
            case 2:
                return iterator.next().getDescription() + " and " + iterator.next().getDescription();
            default:
                StringBuilder builder = new StringBuilder();
                for (int i = 0, count = bindings.size(); i < count; i++) {
                    if (i != 0) {
                        builder.append(", ");
                    }
                    if (i == count - 1) {
                        builder.append("and ");
                    }
                    builder.append(iterator.next().getDescription());
                }
                return builder.toString();
        }
    }

    private static TypeName bestGuess(String type) {
        switch (type) {
            case "void":
                return TypeName.VOID;
            case "boolean":
                return TypeName.BOOLEAN;
            case "byte":
                return TypeName.BYTE;
            case "char":
                return TypeName.CHAR;
            case "double":
                return TypeName.DOUBLE;
            case "float":
                return TypeName.FLOAT;
            case "int":
                return TypeName.INT;
            case "long":
                return TypeName.LONG;
            case "short":
                return TypeName.SHORT;
            default:
                int left = type.indexOf('<');
                if (left != -1) {
                    ClassName typeClassName = ClassName.bestGuess(type.substring(0, left));
                    List<TypeName> typeArguments = new ArrayList<>();
                    do {
                        typeArguments.add(WildcardTypeName.subtypeOf(Object.class));
                        left = type.indexOf('<', left + 1);
                    } while (left != -1);
                    return ParameterizedTypeName.get(typeClassName,
                            typeArguments.toArray(new TypeName[typeArguments.size()]));
                }
                return ClassName.bestGuess(type);
        }
    }

    /**
     * True when this type's bindings require a view hierarchy.
     */
    private boolean hasViewBindings() {
        return !viewBindings.isEmpty() || !collectionBindings.isEmpty();
    }

    private boolean hasMethodBindings() {
        for (ViewBinding bindings : viewBindings) {
            if (!bindings.getMethodBindings().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean hasFieldBindings() {
        for (ViewBinding bindings : viewBindings) {
            if (bindings.getFieldBinding() != null) {
                return true;
            }
        }
        return !collectionBindings.isEmpty();
    }

    private boolean hasTargetField() {
        return hasFieldBindings() || hasMethodBindings();
    }

    private boolean hasViewLocal() {
        for (ViewBinding bindings : viewBindings) {
            if (bindings.requiresLocal()) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if this binding requires a view. Otherwise only a context is needed.
     */
    private boolean constructorNeedsView() {
        return hasViewBindings() //
                || parentBinding != null && parentBinding.constructorNeedsView();
    }

    static boolean requiresCast(TypeName type) {
        return !VIEW_TYPE.equals(type.toString());
    }

    @Override
    public String toString() {
        return bindingClassName.toString();
    }

    static Builder newBuilder(TypeElement enclosingElement) {
        TypeMirror typeMirror = enclosingElement.asType();

        boolean isActivity = isSubtypeOfType(typeMirror, ACTIVITY_TYPE);

        TypeName targetType = TypeName.get(typeMirror);
        if (targetType instanceof ParameterizedTypeName) {
            targetType = ((ParameterizedTypeName) targetType).rawType;
        }

        String packageName = getPackage(enclosingElement).getQualifiedName().toString();
        String className = enclosingElement.getQualifiedName().toString().substring(
                packageName.length() + 1).replace('.', '$');
        ClassName bindingClassName = ClassName.get(packageName, className + "_ViewBinding");

        boolean isFinal = enclosingElement.getModifiers().contains(Modifier.FINAL);
        return new Builder(targetType, bindingClassName, isFinal, isActivity);
    }

    static final class Builder {
        private final TypeName targetTypeName;
        private final ClassName bindingClassName;
        private final boolean isFinal;
        private final boolean isActivity;
        private int layoutId;
        private BindingSet parentBinding;

        private final List<ViewBinding.Builder> viewIdMap = new ArrayList<>();
        private final ImmutableList.Builder<FieldCollectionViewBinding> collectionBindings =
                ImmutableList.builder();

        void setContentLayoutId(@LayoutRes int layoutId) {
            this.layoutId = layoutId;
        }

        private Builder(TypeName targetTypeName, ClassName bindingClassName, boolean isFinal,boolean isActivity) {
            this.targetTypeName = targetTypeName;
            this.bindingClassName = bindingClassName;
            this.isFinal = isFinal;
            this.isActivity = isActivity;
        }

        void addField(Id id, FieldViewBinding binding) {
            getOrCreateViewBindings(id).setFieldBinding(binding);
        }

        void addFieldCollection(FieldCollectionViewBinding binding) {
            collectionBindings.add(binding);
        }

        boolean addMethod(
                Id id,
                ListenerClass listener,
                ListenerMethod method,
                MethodViewBinding binding) {
            ViewBinding.Builder viewBinding = getOrCreateViewBindings(id);
            if (viewBinding.hasMethodBinding(listener, method) && !"void".equals(method.returnType())) {
                return false;
            }
            viewBinding.addMethodBinding(listener, method, binding);
            return true;
        }

        void setParent(BindingSet parent) {
            this.parentBinding = parent;
        }

        private ViewBinding.Builder getOrCreateViewBindings(Id id) {
            ViewBinding.Builder viewId = new ViewBinding.Builder(id);
            viewIdMap.add(viewId);
            return viewId;
        }

        BindingSet build() {
            ImmutableList.Builder<ViewBinding> viewBindings = ImmutableList.builder();
            for (ViewBinding.Builder builder : viewIdMap) {
                viewBindings.add(builder.build());
            }
            return new BindingSet(targetTypeName, bindingClassName, isFinal, isActivity,
                    viewBindings.build(), collectionBindings.build(), parentBinding, layoutId);
        }
    }
}
