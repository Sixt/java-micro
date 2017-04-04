package com.sixt.service.framework.kafka.messaging;

import com.google.protobuf.Parser;
import com.sixt.service.framework.test.api.SayHelloToCmd;
import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A TypeDictionary that finds MessageHandlers as well as Protobuf messages via reflection.
 * <p>
 * The scope of this TypeDictionary is global: all found messages and handlers from the classpath are put to this dictionary.
 * <p>
 * If you want to distinguish e.g. between handlers for the same message type but different topics, this is not the
 * right TypeDictionary implementation.
 */
public final class ReflectionTypeDictionaryFactory {
    private static final Logger logger = LoggerFactory.getLogger(ReflectionTypeDictionaryFactory.class);


    public TypeDictionary createTypeDictionaryFromClasspath() {
        return new TypeDictionary(populateHandlersFromClasspath(), populateParsersFromClasspath());
    }

    private Map<MessageType, MessageHandler<? extends com.google.protobuf.Message>> populateHandlersFromClasspath() {
        Map<MessageType, MessageHandler<? extends com.google.protobuf.Message>> handlers = new HashMap<>();

        List<Class> foundHandlers = new ArrayList<>();

        new FastClasspathScanner()
                .matchClassesImplementing(MessageHandler.class, matchingClass ->
                        foundHandlers.add(matchingClass)).scan();


        foundHandlers.forEach((handlerClass) -> {
            Type[] interfaces = handlerClass.getGenericInterfaces();

            for (Type it : interfaces) {
                if (it instanceof ParameterizedType) {

                    ParameterizedType pt = ((ParameterizedType) it);

                    if (pt.getRawType().getTypeName().equals((MessageHandler.class.getTypeName()))) {
                        // We expect exactly one type argument
                        Type t = pt.getActualTypeArguments()[0];

                        MessageType type = MessageType.of(t);
                        MessageHandler<? extends com.google.protobuf.Message> handler = null;

                        try {
                            // FIXME we would use the guice injector to get an instance
                            // This way, we do not rely on the no-args default constructor.
                            handler = (MessageHandler<? extends com.google.protobuf.Message>) handlerClass.newInstance();
                        } catch (InstantiationException | IllegalAccessException e) {
                            logger.warn("Cannot instantiate MessageHandler {}. Ensure it provides an accessible default constructor.", handlerClass, e);
                        }

                        if (handler != null) {
                            MessageHandler previous = handlers.put(type, handler);
                            if (previous == null) {
                                logger.info("Added message handler {} for type {}", handlerClass, type);
                            } else {
                                logger.warn("Duplicate message handler {} for type {} was replaced by {}", previous.getClass().getTypeName(), type, handlerClass);
                            }
                        }
                    }
                } else {
                    logger.warn("Cannot add untyped instance of MessageHander {} to TypeDictionary", handlerClass.getTypeName());
                }
            }
        });

        return handlers;
    }

    private Map<MessageType, Parser> populateParsersFromClasspath() {
        Map<MessageType, Parser> parsers = new HashMap<>();
        List<Class> foundProtoMessages = new ArrayList<>();

        new FastClasspathScanner()
                .matchSubclassesOf(com.google.protobuf.GeneratedMessageV3.class, matchingClass ->
                        foundProtoMessages.add(matchingClass)).scan();

        // This algorithm adds parsers for all protobuf messages in the classpath including base types such as com.google.protobuf.DoubleValue.


        for (Class clazz : foundProtoMessages) {
            try {
                java.lang.reflect.Method method = clazz.getMethod("parser", null); // static method, no arguments
                Parser parser = (Parser) method.invoke(null, null); // static method, no arguments
                parsers.put(MessageType.of(clazz), parser);

               // too noisy: logger.debug("Added parser for protobuf type {}", clazz.getTypeName());

            } catch (NoSuchMethodException |InvocationTargetException| IllegalAccessException ignored) {
                // too noisy: logger.debug("Ignoring protobuf type {} as we cannot invoke static method parse().", clazz.getTypeName());
            }
        }


        return parsers;
    }


}
