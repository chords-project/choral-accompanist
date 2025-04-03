library(dplyr)
library(ggplot2)

orchestrator <- read.csv("orchestrator_20.csv",	sep	=	";")
choreography <- read.csv("choreography_20.csv", sep = ";")

o <- orchestrator %>%
  group_by(simulated_latency) %>%
  summarize(
    type = "Orch.",
    subtype = "orchestrator",
    latency = mean(total) / 1000000,
  )

# p <- ggplot(o, aes(x = simulated_latency, y = total, fill = simulated_latency)) +
#   geom_bar(position="stack", stat="identity")
# 
# plot(p)

c1 <- choreography %>%
  group_by(simulated_latency) %>%
  summarize(
    type = "Chor.",
    subtype = "sidecar",
    latency = mean(sidecar_latency) / 1000000,
  )

c2 <- choreography %>%
  group_by(simulated_latency) %>%
  summarize(
    type = "Chor.",
    subtype = "choreography",
    latency = (mean(total) - mean(sidecar_latency)) / 1000000,
  )

data <- bind_rows(o, c1, c2)

p <- ggplot(data, aes(x = type, y = latency, fill=subtype)) +
  geom_bar(position="stack", stat="identity") +
  facet_grid(~ simulated_latency) +
  theme(
    #strip.placement = "outside",
    strip.background = element_rect(fill = NA, color = "white"),
    #panel.spacing = unit(-.01,"cm")
  )

plot(p)










