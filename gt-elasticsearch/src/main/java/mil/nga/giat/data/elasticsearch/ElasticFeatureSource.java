/**
 * This file is hereby placed into the Public Domain. This means anyone is
 * free to do whatever they wish with this file.
 */
package mil.nga.giat.data.elasticsearch;

import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.index.query.FilterBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.geotools.data.FeatureReader;
import org.geotools.data.FilteringFeatureReader;
import org.geotools.data.Query;
import org.geotools.data.store.ContentEntry;
import org.geotools.data.store.ContentFeatureSource;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.util.logging.Logging;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;

import static org.opengis.filter.sort.SortOrder.ASCENDING;

import org.opengis.filter.sort.SortBy;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;

/**
 * Provides access to a specific type within the Elasticsearch index described
 * by the associated data store.
 *
 */
@SuppressWarnings("unchecked")
public class ElasticFeatureSource extends ContentFeatureSource {

    private final static Logger LOGGER = Logging.getLogger(ElasticFeatureSource.class);

    private final static int DEFAULT_MAX_FEATURES = 10000;
    
    private Boolean filterFullySupported;

    public ElasticFeatureSource(ContentEntry entry, Query query) throws IOException {
        super(entry, query);
    }

    /**
     * Access parent datastore
     */
    public ElasticDataStore getDataStore() {
        return (ElasticDataStore) super.getDataStore();
    }

    /**
     * Implementation that generates the total bounds
     */
    @Override
    protected ReferencedEnvelope getBoundsInternal(Query query) throws IOException {
        LOGGER.fine("getBoundsInternal");
        final CoordinateReferenceSystem crs;
        crs = getSchema().getCoordinateReferenceSystem();
        final ReferencedEnvelope bounds;
        bounds = new ReferencedEnvelope(crs);

        final FeatureReader<SimpleFeatureType, SimpleFeature> featureReader;
        featureReader = getReaderInternal(query);
        try {
            while (featureReader.hasNext()) {
                final SimpleFeature feature = featureReader.next();
                bounds.include(feature.getBounds());
            }
        } finally {
            featureReader.close();
        }
        return bounds;
    }

    @Override
    protected int getCountInternal(Query query) throws IOException {
        LOGGER.fine("getCountInternal");
        int hits;
        try {
            final SearchRequestBuilder searchRequest;
            searchRequest = prepareSearchRequest(query, SearchType.COUNT);
            if (!filterFullySupported) {
                // need to iterate over features
                hits = 0;
                try (FeatureReader<SimpleFeatureType, SimpleFeature> reader = getReaderInternal(query)) {
                    while (reader.hasNext()) {
                        reader.next();
                        hits++;
                    }
                }
            } else {
                final SearchResponse searchResponse = searchRequest.execute().get();
                final int totalHits = (int) searchResponse.getHits().getTotalHits();
                final int size = getSize(query);
                final int from = getStartIndex(query);
                hits = Math.max(0, Math.min(totalHits-from, size));
            }
        } catch (InterruptedException | ExecutionException e) {
            throw new IOException("Error executing count search", e);
        }

        return hits;
    }

    @Override
    protected FeatureReader<SimpleFeatureType, SimpleFeature> getReaderInternal(Query query)
            throws IOException {
        LOGGER.fine("getReaderInternal");
        FeatureReader<SimpleFeatureType, SimpleFeature> reader = null;
        try {
            final SearchRequestBuilder searchRequest;
            searchRequest = prepareSearchRequest(query, SearchType.DFS_QUERY_THEN_FETCH);
            reader = new ElasticFeatureReader(getState(), searchRequest.execute().get());
            if (!filterFullySupported) {
                reader = new FilteringFeatureReader<SimpleFeatureType, SimpleFeature>(reader, query.getFilter());
            }
        } catch (InterruptedException | ExecutionException e) {
            throw new IOException("Error executing query search", e);
        }
        return reader;
    }

    private SearchRequestBuilder prepareSearchRequest(Query query, 
            SearchType searchType) throws IOException {
        final ElasticDataStore dataStore = getDataStore();
        final String docType = dataStore.getDocType(entry.getName());

        // setup request
        LOGGER.fine("Preparing " + docType + " (" + entry.getName() + ") "  + searchType + " query");
        final SearchRequestBuilder searchRequest;
        searchRequest = dataStore.getClient()
                .prepareSearch(dataStore.getSearchIndices())
                .setTypes(docType)
                .setSearchType(searchType);

        // add fields
        final List<ElasticAttribute> attributes;
        attributes = dataStore.getElasticAttributes(entry.getName());
        List<String> sourceIncludes = new ArrayList<>();
        for (final ElasticAttribute attribute : attributes) {
            if (attribute.isUse() && attribute.isStored()) {
                searchRequest.addField(attribute.getName());
            } else if (attribute.isUse()) {
                sourceIncludes.add(attribute.getName());
            }
        }
        if (sourceIncludes.size() == 1) {
            searchRequest.setFetchSource(sourceIncludes.get(0), null);
        } else if (!sourceIncludes.isEmpty()) {
            final String[] includes;
            includes = sourceIncludes.toArray(new String[sourceIncludes.size()]);
            searchRequest.setFetchSource(includes, null);
        }

        // add query and post filter
        final FilterToElastic filterToElastic;
        filterToElastic = new FilterToElastic();
        filterToElastic.setFeatureType(buildFeatureType());
        filterToElastic.encode(query);
        filterFullySupported = filterToElastic.getFullySupported();
        if (!filterFullySupported) {
            LOGGER.fine("Filter is not fully supported by nativeElasticsearch."
                    + " Additional post-query filtering will be performed.");
        }
        final QueryBuilder elasticQuery = filterToElastic.getQueryBuilder();
        final FilterBuilder postFilter = filterToElastic.getFilterBuilder();
        LOGGER.fine(String.format("postFilter: %s", postFilter.toString()));
        searchRequest.setQuery(elasticQuery).setPostFilter(postFilter);

        // sort
        SortOrder naturalSortOrder = SortOrder.ASC;
        if (query.getSortBy() != null) {
            for (final SortBy sort : query.getSortBy()) {
                final SortOrder sortOrder;
                if (sort.getSortOrder() == ASCENDING) {
                    sortOrder = SortOrder.ASC;
                } else {
                    sortOrder = SortOrder.DESC;
                }

                if (sort.getPropertyName() != null) {
                    final String name = sort.getPropertyName().getPropertyName();
                    searchRequest.addSort(name, sortOrder);
                } else {
                    naturalSortOrder = sortOrder;
                }
            }
        }
        if (elasticQuery.toString().equals(QueryBuilders.matchAllQuery().toString())) {
            searchRequest.addSort("_uid", naturalSortOrder);
        }

        // pagination
        searchRequest.setSize(getSize(query));
        searchRequest.setFrom(getStartIndex(query));
        
        LOGGER.fine(searchRequest.toString());

        return searchRequest;
    }

    private int getSize(Query query) {
        final int size;
        if (query.getMaxFeatures() < Integer.MAX_VALUE) {
            size = query.getMaxFeatures();
        } else {
            size = DEFAULT_MAX_FEATURES;
        }
        return size;
    }

    private int getStartIndex(Query query) {
        final int from;
        if (query.getStartIndex() != null) {
            from = query.getStartIndex();
        } else {
            from = 0;
        }
        return from;
    }

    @Override
    protected SimpleFeatureType buildFeatureType() throws IOException {
        ElasticDataStore ds = getDataStore();
        ElasticLayerConfiguration layerConfig;
        layerConfig = ds.getLayerConfigurations().get(entry.getTypeName());
        final List<ElasticAttribute> attributes;
        if (layerConfig != null) {
            attributes = layerConfig.getAttributes();
        } else {
            attributes = null;
        }
        
        final ElasticFeatureTypeBuilder typeBuilder;
        typeBuilder = new ElasticFeatureTypeBuilder(attributes, entry.getName());
        return typeBuilder.buildFeatureType();
    }

    @Override
    protected boolean canLimit() {
        return true;
    }

    @Override
    protected boolean canOffset() {
        return true;
    }

    @Override
    protected boolean canFilter() {
        return true;
    }

    @Override
    protected boolean canSort() {
        return true;
    }

}
