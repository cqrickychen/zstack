package org.zstack.portal.apimediator;

import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowire;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.transaction.annotation.Transactional;
import org.zstack.core.CoreGlobalProperty;
import org.zstack.core.Platform;
import org.zstack.core.cloudbus.CloudBus;
import org.zstack.core.componentloader.ComponentLoader;
import org.zstack.core.componentloader.PluginRegistry;
import org.zstack.core.db.DatabaseFacade;
import org.zstack.core.errorcode.ErrorFacade;
import org.zstack.header.apimediator.ApiMessageInterceptionException;
import org.zstack.header.apimediator.ApiMessageInterceptor;
import org.zstack.header.apimediator.GlobalApiMessageInterceptor;
import org.zstack.header.apimediator.GlobalApiMessageInterceptor.InterceptorPosition;
import org.zstack.header.apimediator.StopRoutingException;
import org.zstack.header.errorcode.SysErrors;
import org.zstack.header.exception.CloudRuntimeException;
import org.zstack.header.message.*;
import org.zstack.header.rest.RestRequest;
import org.zstack.portal.apimediator.schema.Service;
import org.zstack.utils.DebugUtils;
import org.zstack.utils.FieldUtils;
import org.zstack.utils.TypeUtils;
import org.zstack.utils.Utils;
import org.zstack.utils.function.FunctionNoArg;
import org.zstack.utils.logging.CLogger;
import org.zstack.utils.path.PathUtil;

import javax.persistence.TypedQuery;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.zstack.core.Platform.argerr;

/**
 * Created with IntelliJ IDEA.
 * User: frank
 * Time: 11:46 PM
 * To change this template use File | Settings | File Templates.
 */
@Configurable(preConstruction = true, autowire = Autowire.BY_TYPE)
public class ApiMessageProcessorImpl implements ApiMessageProcessor {
    private static CLogger logger = Utils.getLogger(ApiMessageProcessorImpl.class);
    private Map<Class, ApiMessageDescriptor> descriptors = new HashMap<Class, ApiMessageDescriptor>();
    private Map<Class, Set<GlobalApiMessageInterceptor>> globalInterceptors = new HashMap<Class, Set<GlobalApiMessageInterceptor>>();
    private Set<GlobalApiMessageInterceptor> globalInterceptorsForAllMsg = new HashSet<GlobalApiMessageInterceptor>();

    @Autowired
    private PluginRegistry pluginRgty;
    @Autowired
    private ErrorFacade errf;
    @Autowired
    private DatabaseFacade dbf;
    @Autowired
    private CloudBus bus;

    private boolean unitTestOn;
    private List<String> configFolders;

    private void dump() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Class, ApiMessageDescriptor> e : descriptors.entrySet()) {
            ApiMessageDescriptor desc = e.getValue();
            sb.append("\n-------------------------------------------");
            sb.append(String.format("\nname: %s", desc.getName()));
            sb.append(String.format("\nconfigured service id: %s", desc.getServiceId()));
            sb.append(String.format("\nconfig path: %s", desc.getConfigPath()));
            List<String> inc = new ArrayList<String>();
            for (ApiMessageInterceptor ic : desc.getInterceptors()) {
                inc.add(ic.getClass().getName());
            }
            sb.append(String.format("\ninterceptors: %s", inc));
            sb.append("\n-------------------------------------------");
        }

        logger.debug(String.format("ApiMessageDescriptor dump:\n%s", sb.toString()));
    }

    public ApiMessageProcessorImpl(Map<String, Object> config) {
        this.unitTestOn = CoreGlobalProperty.UNIT_TEST_ON;
        this.configFolders = (List <String>)config.get("serviceConfigFolders");

        populateGlobalInterceptors();

        try {
            JAXBContext context = JAXBContext.newInstance("org.zstack.portal.apimediator.schema");
            List<String> paths = new ArrayList<String>();
            for (String configFolder : this.configFolders) {
                paths.addAll(PathUtil.scanFolderOnClassPath(configFolder));
            }

            for (String p : paths) {
                if (!p.endsWith(".xml")) {
                    logger.warn(String.format("ignore %s which is not ending with .xml", p));
                    continue;
                }

                File cfg = new File(p);
                Unmarshaller unmarshaller = context.createUnmarshaller();
                Service schema = (Service) unmarshaller.unmarshal(cfg);
                createDescriptor(schema, cfg.getAbsolutePath());
            }

            if (!this.unitTestOn) {
                dump();
            }
        } catch (JAXBException e) {
            throw new CloudRuntimeException(e);
        }
    }

    private void prepareInterceptors(ApiMessageDescriptor desc, Service.Message mschema, Service schema) {
        ComponentLoader loader = Platform.getComponentLoader();
        List<ApiMessageInterceptor> interceptors = new ArrayList<ApiMessageInterceptor>();
        List<String> icNames = new ArrayList<String>();
        icNames.addAll(mschema.getInterceptor());
        icNames.addAll(schema.getInterceptor());
        for (String name : icNames) {
            try {
                ApiMessageInterceptor ic = loader.getComponentByBeanName(name);
                interceptors.add(ic);
            } catch (NoSuchBeanDefinitionException ne) {
                if (!this.unitTestOn) {
                    throw new CloudRuntimeException(String.format("Cannot find ApiMessageInterceptor[%s] for message[%s] described in %s. Make sure the ApiMessageInterceptor is configured in spring bean xml file", name, desc.getName(), desc.getConfigPath()), ne);
                }
            }
        }

        Set<GlobalApiMessageInterceptor> gis = new HashSet<GlobalApiMessageInterceptor>();
        for (Map.Entry<Class, Set<GlobalApiMessageInterceptor>> e : globalInterceptors.entrySet()) {
            Class baseMsgClz = e.getKey();
            if (baseMsgClz.isAssignableFrom(desc.getClazz())) {
                gis.addAll(e.getValue());
            }
        }

        List<GlobalApiMessageInterceptor> system = new ArrayList<GlobalApiMessageInterceptor>();
        List<GlobalApiMessageInterceptor> front = new ArrayList<GlobalApiMessageInterceptor>();
        List<GlobalApiMessageInterceptor> end = new ArrayList<GlobalApiMessageInterceptor>();

        for (GlobalApiMessageInterceptor gi : gis) {
            if (logger.isTraceEnabled()) {
                logger.trace(String.format("install GlobalApiMessageInterceptor[%s] to message[%s]", gi.getClass().getName(), desc.getClazz().getName()));
            }

            if (gi.getPosition() == InterceptorPosition.FRONT) {
                front.add(gi);
            } else if (gi.getPosition() == InterceptorPosition.END){
                end.add(gi);
            } else if (gi.getPosition() == InterceptorPosition.SYSTEM) {
                system.add(gi);
            }
        }

        for (GlobalApiMessageInterceptor gi : globalInterceptorsForAllMsg) {
            if (logger.isTraceEnabled()) {
                logger.trace(String.format("install GlobalApiMessageInterceptor[%s] to message[%s]", gi.getClass().getName(), desc.getClazz().getName()));
            }

            if (gi.getPosition() == GlobalApiMessageInterceptor.InterceptorPosition.FRONT) {
                front.add(gi);
            } else if (gi.getPosition() == InterceptorPosition.END){
                end.add(gi);
            } else if (gi.getPosition() == InterceptorPosition.SYSTEM) {
                system.add(gi);
            }
        }

        List<ApiMessageInterceptor> all = new ArrayList<ApiMessageInterceptor>();
        all.addAll(system);
        all.addAll(front);
        all.addAll(interceptors);
        all.addAll(end);

        desc.setInterceptors(all);
    }


    private void createDescriptor(Service schema, String cfgPath) {
        for (Service.Message mschema : schema.getMessage()) {
            Class msgClz = null;
            try {
                msgClz = Class.forName(mschema.getName());
            } catch (ClassNotFoundException e) {
                String err = String.format("unable to create ApiMessageDescriptor for message[name:%s, path:%s]", mschema.getName(), cfgPath);
                throw new CloudRuntimeException(err, e);
            }

            ApiMessageDescriptor old = descriptors.get(msgClz);
            if (old != null) {
                throw new CloudRuntimeException(String.format("Duplicate message description. Message[%s] is described in %s and %s", mschema.getName(), old.getConfigPath(), cfgPath));
            }

            ApiMessageDescriptor desc = new ApiMessageDescriptor();
            desc.setName(mschema.getName());
            String serviceId = mschema.getServiceId() != null ? mschema.getServiceId() : schema.getId();
            desc.setServiceId(serviceId);
            desc.setConfigPath(cfgPath);
            desc.setClazz(msgClz);

            prepareInterceptors(desc, mschema, schema);
            buildApiParams(desc);

            descriptors.put(msgClz, desc);
        }
    }

    private void buildApiParams(ApiMessageDescriptor desc) {
        Class msgClz = desc.getClazz();
        List<Field> fields = FieldUtils.getAllFields(msgClz);

        class FP {
            Field field;
            APIParam param;
        }

        Map<String, FP> fmap = new HashMap<String, FP>();
        for (Field f : fields) {
            APIParam at = f.getAnnotation(APIParam.class);
            if (at == null) {
                continue;
            }

            FP fp = new FP();
            fp.field = f;
            fp.param = f.getAnnotation(APIParam.class);
            fmap.put(f.getName(), fp);
        }

        OverriddenApiParams at = desc.getClazz().getAnnotation(OverriddenApiParams.class);
        if (at != null) {
            for (OverriddenApiParam atp : at.value()) {
                Field f = FieldUtils.getField(atp.field(), msgClz);
                if (f == null) {
                    throw new CloudRuntimeException(String.format("cannot find the field[%s] specified in @OverriddenApiParam of class[%s]",
                            atp.field(), msgClz));
                }

                FP fp = new FP();
                fp.field = f;
                fp.param = atp.param();
                fmap.put(atp.field(), fp);
            }
        }

        for (FP fp : fmap.values()) {
            desc.getFieldApiParams().put(fp.field, fp.param);
        }
    }


    private void apiParamValidation(APIMessage msg) {
        try {
            msg.validate((msg1, f, value, at) -> {
                if (value != null && at.resourceType() != Object.class) {
                    if (value instanceof Collection) {
                        final Collection col = (Collection) value;
                        if (!col.isEmpty()) {
                            List<String> uuids = new FunctionNoArg<List<String>>() {
                                @Override
                                @Transactional(readOnly = true)
                                public List<String> call() {
                                    String sql = String.format("select e.uuid from %s e where e.uuid in (:uuids)", at.resourceType().getSimpleName());
                                    TypedQuery<String> q = dbf.getEntityManager().createQuery(sql, String.class);
                                    q.setParameter("uuids", col);
                                    return q.getResultList();
                                }
                            }.call();

                            if (uuids.size() != col.size()) {
                                List<String> invalids = new ArrayList<>();
                                for (Object o : col) {
                                    String uuid = (String) o;
                                    if (!uuids.contains(uuid)) {
                                        invalids.add(uuid);
                                    }
                                }

                                if (!invalids.isEmpty()) {
                                    throw new ApiMessageInterceptionException(errf.instantiateErrorCode(SysErrors.RESOURCE_NOT_FOUND,
                                            String.format("invalid field[%s], resource[uuids:%s, type:%s] not found", f.getName(), invalids, at.resourceType().getSimpleName())
                                    ));
                                }
                            }
                        }

                    } else {
                        DebugUtils.Assert(String.class.isAssignableFrom(f.getType()), String.format("field[%s] of message[%s] has APIParam.resourceType specified, then the field must be uuid which is a String, but actual is %s",
                                f.getName(), msg.getClass().getName(), f.getType()));

                        if (!dbf.isExist(value, at.resourceType())) {
                            if (at.successIfResourceNotExisting()) {
                                RestRequest rat = msg.getClass().getAnnotation(RestRequest.class);
                                if (rat == null) {
                                    throw new CloudRuntimeException(String.format("the API class[%s] does not have @RestRequest but it uses a successIfResourceNotExisting helper", msg.getClass()));
                                }

                                Pattern p = Pattern.compile("[0-9a-f]{8}[0-9a-f]{4}[1-5][0-9a-f]{3}[89ab][0-9a-f]{3}[0-9a-f]{12}");
                                Matcher mt = p.matcher(value.toString());
                                if (!mt.matches()){
                                    throw new ApiMessageInterceptionException(errf.instantiateErrorCode(SysErrors.RESOURCE_NOT_FOUND,
                                            String.format("invalid value[%s] of field [%s]", value, f.getName())));
                                }

                                APIEvent evt;
                                try {
                                    evt = (APIEvent) rat.responseClass().getConstructor(String.class).newInstance(msg.getId());
                                } catch (Exception e) {
                                    throw new CloudRuntimeException(e);
                                }

                                bus.publish(evt);
                                throw new StopRoutingException();
                            } else {
                                throw new ApiMessageInterceptionException(errf.instantiateErrorCode(SysErrors.RESOURCE_NOT_FOUND,
                                        String.format("invalid field[%s], resource[uuid:%s, type:%s] not found", f.getName(), value, at.resourceType().getSimpleName())
                                ));
                            }
                        }
                    }
                }
            });
        } catch (ApiMessageInterceptionException | StopRoutingException ae) {
            throw ae;
        } catch (APIMessage.InvalidApiMessageException ie) {
            throw new ApiMessageInterceptionException(argerr(ie.getMessage(), ie.getArguments()));
        } catch (Exception e) {
            logger.warn(e.getMessage(), e);
            throw new ApiMessageInterceptionException(errf.throwableToInternalError(e));
        }
    }

    @Override
    public APIMessage process(APIMessage msg) throws ApiMessageInterceptionException {
        ApiMessageDescriptor desc = descriptors.get(msg.getClass());

        apiParamValidation(msg);
        if (desc == null) {
            throw new CloudRuntimeException(String.format("Message[%s] has no ApiMessageDescriptor", msg.getClass().getName()));
        }

        for (ApiMessageInterceptor ic : desc.getInterceptors()) {
            msg = ic.intercept(msg);
        }

        return msg;
    }

    @Override
    public ApiMessageDescriptor getApiMessageDescriptor(APIMessage msg) {
        return descriptors.get(msg.getClass());
    }

    private void populateGlobalInterceptors() {
        for (GlobalApiMessageInterceptor gi : pluginRgty.getExtensionList(GlobalApiMessageInterceptor.class)) {
            if (gi.getMessageClassToIntercept() == null) {
                globalInterceptorsForAllMsg.add(gi);
            } else {
                for (Class msgClz : gi.getMessageClassToIntercept()) {
                    Set<GlobalApiMessageInterceptor> gis = globalInterceptors.get(msgClz);
                    if (gis == null) {
                        gis = new HashSet<GlobalApiMessageInterceptor>();
                        globalInterceptors.put(msgClz, gis);
                    }
                    gis.add(gi);
                }
            }
        }
    }
}
