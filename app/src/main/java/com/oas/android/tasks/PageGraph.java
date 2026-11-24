package com.oas.android.tasks;

import java.util.*;

/** Bounded graph search; edges are explicit OAS UI transitions, never a generic back button. */
public final class PageGraph {
    public static final class Edge {
        public final String from,to,click;
        public Edge(String from,String to,String click){this.from=from;this.to=to;this.click=click;}
    }
    private final List<Edge> edges=new ArrayList<>();
    public PageGraph connect(String from,String to,String click){edges.add(new Edge(from,to,click));return this;}
    public List<Edge> route(String from,String to){
        if(from.equals(to))return Collections.emptyList();
        Queue<String> queue=new ArrayDeque<>();Map<String,Edge> seen=new HashMap<>();Set<String> visited=new HashSet<>();
        queue.add(from);visited.add(from);
        while(!queue.isEmpty()) {
            String current=queue.remove();
            for(Edge edge:edges) if(edge.from.equals(current)&&visited.add(edge.to)) {
                seen.put(edge.to,edge);
                if(edge.to.equals(to)) {
                    LinkedList<Edge> path=new LinkedList<>();String node=to;
                    while(!node.equals(from)){Edge previous=seen.get(node);path.addFirst(previous);node=previous.from;}
                    return path;
                }
                queue.add(edge.to);
            }
        }
        throw new IllegalStateException("没有已知导航路径："+from+" → "+to);
    }
}
