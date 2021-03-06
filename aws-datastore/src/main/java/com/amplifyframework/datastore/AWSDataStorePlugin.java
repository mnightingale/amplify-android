/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.amplifyframework.datastore;

import android.annotation.SuppressLint;
import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import com.amplifyframework.AmplifyException;
import com.amplifyframework.api.ApiCategory;
import com.amplifyframework.api.graphql.GraphQLBehavior;
import com.amplifyframework.core.Action;
import com.amplifyframework.core.Amplify;
import com.amplifyframework.core.Consumer;
import com.amplifyframework.core.InitializationStatus;
import com.amplifyframework.core.async.Cancelable;
import com.amplifyframework.core.model.Model;
import com.amplifyframework.core.model.ModelProvider;
import com.amplifyframework.core.model.ModelSchemaRegistry;
import com.amplifyframework.core.model.query.QueryOptions;
import com.amplifyframework.core.model.query.Where;
import com.amplifyframework.core.model.query.predicate.QueryPredicate;
import com.amplifyframework.core.model.query.predicate.QueryPredicates;
import com.amplifyframework.datastore.appsync.AppSyncClient;
import com.amplifyframework.datastore.model.ModelProviderLocator;
import com.amplifyframework.datastore.storage.LocalStorageAdapter;
import com.amplifyframework.datastore.storage.StorageItemChange;
import com.amplifyframework.datastore.storage.sqlite.SQLiteStorageAdapter;
import com.amplifyframework.datastore.syncengine.Orchestrator;
import com.amplifyframework.hub.HubChannel;
import com.amplifyframework.logging.Logger;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.reactivex.Completable;

/**
 * An AWS implementation of the {@link DataStorePlugin}.
 */
public final class AWSDataStorePlugin extends DataStorePlugin<Void> {
    private static final Logger LOG = Amplify.Logging.forNamespace("amplify:aws-datastore");
    private static final long PLUGIN_INIT_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(5);
    // Reference to an implementation of the Local Storage Adapter that
    // manages the persistence of data on-device.
    private final LocalStorageAdapter sqliteStorageAdapter;

    // A component which synchronizes data state between the
    // local storage adapter, and a remote API
    private final Orchestrator orchestrator;

    // Keeps track of whether of not the category is initialized yet
    private final CountDownLatch categoryInitializationsPending;

    // Used to interrogate plugins, to understand if sync should be automatically turned on
    private final ApiCategory api;

    // User-provided configuration for the plugin.
    private final DataStoreConfiguration userProvidedConfiguration;

    // Configuration for the plugin that contains settings from the JSON file plus any
    // overrides provided via the userProvidedConfiguration
    private DataStoreConfiguration pluginConfiguration;

    @SuppressLint("CheckResult")
    private AWSDataStorePlugin(
            @NonNull ModelProvider modelProvider,
            @NonNull ModelSchemaRegistry modelSchemaRegistry,
            @NonNull ApiCategory api,
            @Nullable DataStoreConfiguration userProvidedConfiguration) {
        this.sqliteStorageAdapter = SQLiteStorageAdapter.forModels(modelSchemaRegistry, modelProvider);
        this.categoryInitializationsPending = new CountDownLatch(1);
        this.api = api;
        this.orchestrator = new Orchestrator(
            modelProvider,
            modelSchemaRegistry,
            sqliteStorageAdapter,
            AppSyncClient.via(api),
            () -> pluginConfiguration
        );
        this.userProvidedConfiguration = userProvidedConfiguration;
    }

    /**
     * Constructs an {@link AWSDataStorePlugin} which can warehouse the model types provided by
     * your application's code-generated model provider. This model provider is expected to have the
     * fully qualified class name of com.amplifyframework.datastore.generated.model.AmplifyModelProvider.
     *
     * This constructor will attempt to find that provide by means of reflection. If you have changed
     * the path to your {@link ModelProvider} instance, and do not use the default path generated by
     * the Amplify Code Generator, then this will break. Likewise, if you need to provide a custom
     * {@link ModelProvider}, this will not work. In both cases, you should prefer one of the other
     * overloads such as {@link #AWSDataStorePlugin(ModelProvider)}.
     *
     * If remote synchronization is enabled, it will be performed via {@link Amplify#API}.
     *
     * @throws DataStoreException If it is not possible to access the code-generated model provider
     */
    @SuppressWarnings("unused") // This is a public API.
    public AWSDataStorePlugin() throws DataStoreException {
        this(ModelProviderLocator.locate(), Amplify.API);
    }

    /**
     * Constructs an {@link AWSDataStorePlugin} which can warehouse the model types provided by
     * the supplied {@link ModelProvider}. If the API plugin is present and configured,
     * then remote synchronization will be performed through {@link Amplify#API}.
     * @param modelProvider Provider of models to be usable by plugin
     */
    @SuppressWarnings({"unused", "WeakerAccess"}) // This is a public API.
    public AWSDataStorePlugin(@NonNull ModelProvider modelProvider) {
        this(Objects.requireNonNull(modelProvider), Amplify.API);
    }

    /**
     * Constructs an {@link AWSDataStorePlugin} which can warehouse the model types provided by the
     * supplied {@link ModelProvider}. If remote synchronization is enabled, it will be performed
     * through the provided {@link GraphQLBehavior}.
     * @param modelProvider Provides the set of models to be warehouse-able by this system
     * @param api Interface to a remote system where models will be synchronized
     */
    @VisibleForTesting
    AWSDataStorePlugin(@NonNull ModelProvider modelProvider, @NonNull ApiCategory api) {
        this(
            Objects.requireNonNull(modelProvider),
            ModelSchemaRegistry.instance(),
            Objects.requireNonNull(api),
            null
        );
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public String getPluginKey() {
        return DataStoreConfiguration.PLUGIN_CONFIG_KEY;
    }

    /**
     * {@inheritDoc}
     */
    @SuppressLint("CheckResult")
    @Override
    public void configure(
            @NonNull JSONObject pluginConfiguration,
            @NonNull Context context
    ) throws DataStoreException {
        try {
            // Applies user-provided configs on-top-of any values from the file.
            this.pluginConfiguration = DataStoreConfiguration
                .builder(pluginConfiguration, userProvidedConfiguration)
                .build();
        } catch (DataStoreException badConfigException) {
            throw new DataStoreException(
                "There was an issue configuring the plugin from the amplifyconfiguration.json",
                badConfigException,
                "Check the attached exception for more details and " +
                    "be sure you are only calling Amplify.configure once"
            );
        }

        HubChannel hubChannel = HubChannel.forCategoryType(getCategoryType());
        Amplify.Hub.subscribe(hubChannel,
            event -> InitializationStatus.SUCCEEDED.toString().equals(event.getName()),
            event -> categoryInitializationsPending.countDown()
        );
    }

    @WorkerThread
    @Override
    public void initialize(@NonNull Context context) throws AmplifyException {
        Throwable initError = initializeStorageAdapter(context)
            .andThen(initializeOrchestrator())
            .blockingGet(PLUGIN_INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (initError != null) {
            throw new AmplifyException("Failed to initialize the local storage adapter for the DataStore plugin.",
                                        initError,
                                        AmplifyException.TODO_RECOVERY_SUGGESTION);
        }
    }

    /**
     * Initializes the storage adapter, and gets the result as a {@link Completable}.
     * @param context An Android Context
     * @return A Completable which will initialize the storage adapter when subscribed.
     */
    @WorkerThread
    private Completable initializeStorageAdapter(Context context) {
        return Completable.defer(() -> Completable.create(emitter ->
            sqliteStorageAdapter.initialize(context, schemaList -> emitter.onComplete(), emitter::onError)
        ));
    }

    /**
     * Terminate use of the plugin.
     * @throws AmplifyException On failure to terminate use of the plugin
     */
    @SuppressWarnings("unused")
    synchronized void terminate() throws AmplifyException {
        orchestrator.stop();
        sqliteStorageAdapter.terminate();
    }

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public Void getEscapeHatch() {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends Model> void save(
            @NonNull T item,
            @NonNull Consumer<DataStoreItemChange<T>> onItemSaved,
            @NonNull Consumer<DataStoreException> onFailureToSave) {
        save(item, QueryPredicates.all(), onItemSaved, onFailureToSave);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends Model> void save(
            @NonNull T item,
            @NonNull QueryPredicate predicate,
            @NonNull Consumer<DataStoreItemChange<T>> onItemSaved,
            @NonNull Consumer<DataStoreException> onFailureToSave) {
        beforeOperation(() -> sqliteStorageAdapter.save(
            item,
            StorageItemChange.Initiator.DATA_STORE_API,
            predicate,
            itemSave -> {
                try {
                    onItemSaved.accept(toDataStoreItemChange(itemSave));
                } catch (DataStoreException dataStoreException) {
                    onFailureToSave.accept(dataStoreException);
                }
            },
            onFailureToSave
        ));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends Model> void delete(
            @NonNull T item,
            @NonNull Consumer<DataStoreItemChange<T>> onItemDeleted,
            @NonNull Consumer<DataStoreException> onFailureToDelete) {
        delete(item, QueryPredicates.all(), onItemDeleted, onFailureToDelete);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends Model> void delete(
            @NonNull T item,
            @NonNull QueryPredicate predicate,
            @NonNull Consumer<DataStoreItemChange<T>> onItemDeleted,
            @NonNull Consumer<DataStoreException> onFailureToDelete) {
        beforeOperation(() -> sqliteStorageAdapter.delete(
            item,
            StorageItemChange.Initiator.DATA_STORE_API,
            itemDeletion -> {
                try {
                    onItemDeleted.accept(toDataStoreItemChange(itemDeletion));
                } catch (DataStoreException dataStoreException) {
                    onFailureToDelete.accept(dataStoreException);
                }
            },
            onFailureToDelete
        ));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends Model> void query(
            @NonNull Class<T> itemClass,
            @NonNull Consumer<Iterator<T>> onQueryResults,
            @NonNull Consumer<DataStoreException> onQueryFailure) {
        beforeOperation(() -> sqliteStorageAdapter.query(itemClass, onQueryResults, onQueryFailure));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends Model> void query(
            @NonNull Class<T> itemClass,
            @NonNull QueryPredicate queryPredicate,
            @NonNull Consumer<Iterator<T>> onQueryResults,
            @NonNull Consumer<DataStoreException> onQueryFailure) {
        this.query(itemClass, Where.matches(queryPredicate), onQueryResults, onQueryFailure);
    }

    @Override
    public <T extends Model> void query(
            @NonNull Class<T> itemClass,
            @NonNull QueryOptions options,
            @NonNull Consumer<Iterator<T>> onQueryResults,
            @NonNull Consumer<DataStoreException> onQueryFailure) {
        beforeOperation(() ->
                sqliteStorageAdapter.query(itemClass, options, onQueryResults, onQueryFailure));
    }

    @Override
    public void observe(
            @NonNull Consumer<Cancelable> onObservationStarted,
            @NonNull Consumer<DataStoreItemChange<? extends Model>> onDataStoreItemChange,
            @NonNull Consumer<DataStoreException> onObservationFailure,
            @NonNull Action onObservationCompleted) {
        beforeOperation(() -> onObservationStarted.accept(sqliteStorageAdapter.observe(
            itemChange -> {
                try {
                    onDataStoreItemChange.accept(toDataStoreItemChange(itemChange));
                } catch (DataStoreException dataStoreException) {
                    onObservationFailure.accept(dataStoreException);
                }
            },
            onObservationFailure,
            onObservationCompleted
        )));
    }

    @Override
    public <T extends Model> void observe(
            @NonNull Class<T> itemClass,
            @NonNull Consumer<Cancelable> onObservationStarted,
            @NonNull Consumer<DataStoreItemChange<T>> onDataStoreItemChange,
            @NonNull Consumer<DataStoreException> onObservationFailure,
            @NonNull Action onObservationCompleted) {
        beforeOperation(() -> onObservationStarted.accept(sqliteStorageAdapter.observe(
            itemChange -> {
                try {
                    if (itemChange.itemClass().equals(itemClass)) {
                        @SuppressWarnings("unchecked") // This was just checked, right above.
                        StorageItemChange<T> typedChange = (StorageItemChange<T>) itemChange;
                        onDataStoreItemChange.accept(toDataStoreItemChange(typedChange));
                    }
                } catch (DataStoreException dataStoreException) {
                    onObservationFailure.accept(dataStoreException);
                }
            },
            onObservationFailure,
            onObservationCompleted
        )));
    }

    @Override
    public <T extends Model> void observe(
            @NonNull Class<T> itemClass,
            @NonNull String uniqueId,
            @NonNull Consumer<Cancelable> onObservationStarted,
            @NonNull Consumer<DataStoreItemChange<T>> onDataStoreItemChange,
            @NonNull Consumer<DataStoreException> onObservationFailure,
            @NonNull Action onObservationCompleted) {
        beforeOperation(() -> onObservationStarted.accept(sqliteStorageAdapter.observe(
            itemChange -> {
                try {
                    if (itemChange.itemClass().equals(itemClass) && itemChange.item().getId().equals(uniqueId)) {
                        @SuppressWarnings("unchecked") // itemClass() was just inspected above. This is safe.
                        StorageItemChange<T> typedChange = (StorageItemChange<T>) itemChange;
                        onDataStoreItemChange.accept(toDataStoreItemChange(typedChange));
                    }
                } catch (DataStoreException dataStoreException) {
                    onObservationFailure.accept(dataStoreException);
                }
            },
            onObservationFailure,
            onObservationCompleted
        )));
    }

    @Override
    public <T extends Model> void observe(
            @NonNull Class<T> itemClass,
            @NonNull QueryPredicate selectionCriteria,
            @NonNull Consumer<Cancelable> onObservationStarted,
            @NonNull Consumer<DataStoreItemChange<T>> onDataStoreItemChange,
            @NonNull Consumer<DataStoreException> onObservationFailure,
            @NonNull Action onObservationCompleted) {
        onObservationFailure.accept(new DataStoreException("Not implemented yet, buster!", "Check back later!"));
    }

    /**
     * Stops all synchronization processes and invokes
     * the clear method of the underlying storage
     * adapter. Any items pending synchronization in the outbound queue will
     * be lost. Synchronization processes will be restarted on the
     * next interaction with the DataStore.
     * @param onComplete Invoked if the call is successful.
     * @param onError Invoked if not successful
     */
    @Override
    public void clear(@NonNull Action onComplete,
                      @NonNull Consumer<DataStoreException> onError) {
        beforeOperation(() -> {
            orchestrator.stop();
            sqliteStorageAdapter.clear(onComplete, onError);
        });
    }

    private void beforeOperation(@NonNull final Runnable runnable) {
        Completable opCompletable = Completable.fromAction(categoryInitializationsPending::await);
        if (!orchestrator.isStarted()) {
            opCompletable = opCompletable
                .andThen(initializeOrchestrator());
        }
        Throwable throwable = opCompletable
            .andThen(Completable.fromRunnable(runnable))
            .blockingGet(PLUGIN_INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (throwable != null) {
            LOG.warn("Failed to execute request due to an unexpected error.", throwable);
        }
    }

    private Completable initializeOrchestrator() {
        if (api.getPlugins().isEmpty()) {
            return Completable.complete();
        } else {
            return orchestrator.start();
        }
    }

    /**
     * Converts an {@link StorageItemChange} into an {@link DataStoreItemChange}.
     * @param storageItemChange A storage item change
     * @param <T> Type of data that was changed in the storage layer
     * @return A data store item change representing the change in storage layer
     */
    private static <T extends Model> DataStoreItemChange<T> toDataStoreItemChange(
            final StorageItemChange<T> storageItemChange) throws DataStoreException {

        final DataStoreItemChange.Initiator dataStoreItemChangeInitiator;
        switch (storageItemChange.initiator()) {
            case SYNC_ENGINE:
                dataStoreItemChangeInitiator = DataStoreItemChange.Initiator.REMOTE;
                break;
            case DATA_STORE_API:
                dataStoreItemChangeInitiator = DataStoreItemChange.Initiator.LOCAL;
                break;
            default:
                throw new DataStoreException(
                        "Unknown initiator of storage change: " + storageItemChange.initiator(),
                        AmplifyException.TODO_RECOVERY_SUGGESTION
                );
        }

        final DataStoreItemChange.Type dataStoreItemChangeType;
        switch (storageItemChange.type()) {
            case DELETE:
                dataStoreItemChangeType = DataStoreItemChange.Type.DELETE;
                break;
            case UPDATE:
                dataStoreItemChangeType = DataStoreItemChange.Type.UPDATE;
                break;
            case CREATE:
                dataStoreItemChangeType = DataStoreItemChange.Type.CREATE;
                break;
            default:
                throw new DataStoreException(
                        "Unknown type of storage change: " + storageItemChange.type(),
                        AmplifyException.TODO_RECOVERY_SUGGESTION
                );
        }

        return DataStoreItemChange.<T>builder()
            .initiator(dataStoreItemChangeInitiator)
            .item(storageItemChange.item())
            .itemClass(storageItemChange.itemClass())
            .type(dataStoreItemChangeType)
            .uuid(storageItemChange.changeId().toString())
            .build();
    }
}
