package org.learn.nosql.utils;

import java.io.FileReader;
import java.util.*;
import java.util.stream.Collectors;

public class SystemConfig {
    private final Map<String,String> _kvPair = new LinkedHashMap<>();

    public SystemConfig load(String... configs) {
        Arrays.stream(configs)
                .map(fName -> this.getClass().getClassLoader().getResource(fName).getFile())
                .map(path -> {
            FileReader _fReader = null;
            try {
                _fReader = new FileReader(path);
            } catch (Exception cause) {
                System.err.println("Failed to read configs from : " + path.toString());
            }
            return _fReader;
        }).forEach(reader -> {
            Properties _props = null ;
            try {
                _props = new Properties();
                _props.load(reader);
                Set<Map.Entry<Object, Object>> entries = _props.entrySet();
                entries.forEach(entry -> _kvPair.put(entry.getKey().toString(),entry.getValue().toString()));
                _kvPair.keySet().stream().forEach(key -> System.setProperty(key,_kvPair.get(key)));
            } catch(Exception cause) {
                System.err.println("Failed to update configs !");
            }
        });
        return this;
    }

    public Optional<String> getValue(String key) {
        return Optional.ofNullable(_kvPair.get(key));
    }

    public boolean isDefined(String key) {
        return _kvPair.containsKey(key);
    }

    public Optional<Set<Map.Entry<String, String>>> keyMatches(String substring) {
        Set<String> matched_keys = _kvPair.keySet()
                .stream()
                .filter(key -> key.indexOf(substring) != -1)
                .collect(Collectors.toSet());

        Set<Map.Entry<String, String>> entries        = _kvPair.entrySet();
        Set<Map.Entry<String, String>> matchedEntries = entries.stream()
                .filter(entry -> matched_keys.contains(entry.getKey()))
                .collect(Collectors.toSet());
        return Optional.ofNullable(matchedEntries);
    }
}
